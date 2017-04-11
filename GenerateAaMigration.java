import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.ProcessBuilder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generator of migration scripts for ActiveAndroid
 * Must be invoked with a model file name to migrate, and optionnally an SHA1 commit ID
 * If the commit ID is provided, the ancestor is assumed to be the parent of this ID, and the new version is this ID
 * If the commit ID is not provided, the ancestor is assumed to be HEAD and the new version is the actual file.
 */
class GenerateAaMigration {
    private static final String COLUMN_DECLARATION = "(public)?\\s*([^ ]+)\\s*([^\\s]+)\\s*;";

    /**
     * This awk script reads a file that contains TypeSerializer classes
     * The output is a key/value pair of source and destination types, one per line.
     * For example for a TypeSerializer that stores Calendar as Long, the output would be "Calendar Long"
     */
    private static final String AWK_GET_SOURCE_DESTINATION_TYPES = 
                "BEGIN { FS=\"[; \\.]\" } " +
                "/TypeSerializer/ { " +
                "  if (des != \"\") { printf \"%s=%s\\n\", des, ser; } " +
                "  des=\"\"; ser=\"\";" +
                "} "+
                "$0 ~ /getDeserializedType/ { desNR=NR } " +
                "desNR && $0 ~ /return/ {des=$(NF-2); desNR=0;} " +
                "$0 ~ /getSerializedType/ { serNR=NR } " +
                "serNR && $0 ~ /return/ {ser=$(NF-2); serNR=0;} " +
                "END { printf \"%s %s\\n\", des, ser; }";

    /**
     * Program entry point
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Invoke at least with model.java file");
        }

        File model = new File(args[0]);
        if (!model.exists()) {
            throw new IllegalArgumentException("Model file doesn't exist: " + model);
        }
        String sha1 = args.length > 1 ? args[1] : null;

        // Get the model before and after migration
        Model fromModel, toModel;
        if (sha1 == null) {
            fromModel = readModel("HEAD", model.toString());
            toModel = readModel(null, model.toString());
        } else {
            fromModel = readModel(sha1 + "~1", model.toString());
            toModel = readModel(sha1, model.toString());
        }

        // Get the list of custom types that there may exist in the model class (or its ancestors)
        Map<String, String> types = getCustomTypes();

        // Do the diff and print a migration script
        new Migration(fromModel, toModel, types).writeMigrationScript();
    }

    /**
     * Actual migration script generator
     * Requires the from/to models and the list of custom types
     */
    private static class Migration {
        List<Column> addedColumns = new ArrayList<>();
        List<Column> removedColumns = new ArrayList<>();
        Model model;
        Map<String, String> types;
        List<Column> commonFields = new ArrayList<>();

        public Migration(Model from, Model to, Map<String, String> types) {
            this.types = types;
            model = to;

            removedColumns.addAll(from.fields);

            for (Column col : to.fields) {
                Column oldCol = from.search(col);
                if (oldCol == null) {
                    addedColumns.add(col);
                } else if (oldCol.equals(col)) {
                    commonFields.add(col);
                    removedColumns.remove(oldCol);
                } else {
                    throw new IllegalArgumentException("Changing a column type is not supported: " + col + " (was: " + oldCol + ")");
                }
            }
        }

        public void writeMigrationScript() throws IOException {
            if (removedColumns.size() == 0 && addedColumns.size() == 0) {
                System.out.println("No migration needed!");
                return;
            }

            System.out.println("BEGIN TRANSACTION;");
            System.out.println("");

            if (removedColumns.size() > 0) {
                System.out.println(computeCreateStatement(model, types)
                        .replaceAll(
                            "CREATE TABLE " + model.tableName,
                            "CREATE TABLE " + model.tableName + "_backup"
                    )
                );
                System.out.print("INSERT INTO " + model.tableName + "_backup (Id");
                for (Column column : commonFields) {
                    System.out.print(", " + column.name);
                }
                System.out.print(") SELECT Id");
                for (Column column : commonFields) {
                    System.out.print(", " + column.name);
                }
                System.out.println(" FROM " + model.tableName + ";");
                System.out.println("DROP TABLE " + model.tableName + ";");
                System.out.println("ALTER TABLE " + model.tableName + "_backup RENAME TO " + model.tableName + ";");
            } else {
                for (Column column : addedColumns) {
                    System.out.println(String.format("ALTER TABLE %s ADD COLUMN %s %s;",
                                model.tableName,
                                column.name,
                                getColumnType(column, types)
                    ));
                }
            }

            System.out.println("");
            System.out.println("COMMIT;");
        }
    }

    /**
     * Representation of a model class
     */
    private static class Model {
        public String tableName = null;
        public List<Column> fields = new ArrayList<>();

        /**
         * Search by column name only
         */
        public Column search(Column needle) {
            for (Column col : fields) {
                if (col.name != null && col.name.equals(needle.name)) {
                    return col;
                }
            }
            return null;
        }
    }

    /**
     * Create a buffered reader on the file at the desired commit
     * If the SHA1 commit ID is null, the current file is loaded
     * If the SHA1 commit ID is not null, the file at that revision is loaded
     */
    private static BufferedReader openFileAt(String file, String sha1) throws IOException {
        ProcessBuilder builder;
        if (sha1 == null) {
            builder = new ProcessBuilder("cat", file);
        } else {
            builder = new ProcessBuilder("git", "--no-pager", "show", sha1 + ":" + file);
        }
        builder.redirectErrorStream(true);
        Process p = builder.start();
        return new BufferedReader(new InputStreamReader(p.getInputStream()));
    }

    /**
     * Parse a model class file
     */
    private static Model readModel(String sha1, String modelFile) throws IOException {
        if (!modelFile.endsWith("/com/activeandroid/Model.java")
                && !modelFile.endsWith("/java/util/Calendar.java")
                && !(new File(modelFile).exists())) {
            throw new IllegalArgumentException("Unable to open model file: " + modelFile);
        }

        // Various regexes used to parse the interesting sections of the file
        Pattern column = Pattern.compile(COLUMN_DECLARATION);
        Pattern table = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*([^\\)]+)\\)");
        Pattern pkg = Pattern.compile("package\\s+([^\\s;]+)\\s*;");
        Pattern imp = Pattern.compile("import\\s+([^\\s;]+)\\s*;");
        Pattern ext = Pattern.compile("extends\\s+([^\\s\\{]+)\\s*\\{?");
        Pattern keys = Pattern.compile("on(Delete|Update)\\s*=\\s*.*(SET_NULL|SET_DEFAULT|CASCADE|RESTRICT|NO_ACTION)");

        Model model = new Model();
        String line, packagePath = "";
        Map<String, String> imports = new HashMap<>();
        BufferedReader r = openFileAt(modelFile, sha1);
        while (true) {
            line = r.readLine();
            if (line == null) {
                break;
            }

            // Load package name as a relative path
            Matcher pkgMatcher = pkg.matcher(line);
            if (pkgMatcher.find()) {
                packagePath = modelFile.substring(0, modelFile.indexOf(pkgMatcher.group(1).replaceAll("\\.", "/")));
            }
            
            // Load imports
            Matcher importMatcher = imp.matcher(line);
            if (importMatcher.find()) {
                imports.put(
                        importMatcher.group(1).substring(importMatcher.group(1).lastIndexOf(".") + 1), // class name (without the package name)
                        importMatcher.group(1).replaceAll("\\.", "/") // file path
                );
            }

            // See if we subclass another model
            Matcher subclass = ext.matcher(line);
            if (subclass.find()) {
                if (imports.containsKey(subclass.group(1))) {
                    // Add all the parent's fields
                    Model parent = readModel(sha1, packagePath + imports.get(subclass.group(1)) + ".java");
                    model.fields.addAll(parent.fields);
                }
            }

            // See if the @Table annotation contains the table name
            if (model.tableName == null) {
                Matcher tableMatcher = table.matcher(line);
                if (tableMatcher.find()) {
                    model.tableName = tableMatcher.group(1);
                }
            }

            // Ah, new column!
            if (line.contains("@Column")) {
                // See if it's a foreign key with onDelete and onUpdate instructions
                Matcher keysMatcher = keys.matcher(line);
                String upd = null;
                String del = null;
                while (keysMatcher.find()) {
                    if ("Update".equals(keysMatcher.group(1))) {
                        upd = keysMatcher.group(2).replaceAll("_", " ");
                    }
                    if ("Delete".equals(keysMatcher.group(1))) {
                        del = keysMatcher.group(2).replaceAll("_", " ");
                    }
                }

                // Parse the column declaration
                do {
                    Matcher colMatcher = column.matcher(line);
                    if (colMatcher.find()) {
                        Column col = new Column(colMatcher.group(2), colMatcher.group(3));
                        if (del != null) {
                            col.delete = del;
                        }
                        if (upd != null) {
                            col.update = upd;
                        }

                        // If the column type is imported and is a model, save the foreign key table name
                        if (imports.containsKey(colMatcher.group(2))) {
                            col.fk = readModel(sha1, packagePath + imports.get(colMatcher.group(2)) + ".java").tableName;
                        }

                        model.fields.add(col);
                        break;
                    }
                } while ((line = r.readLine()) != null);
            }
        }

        // If the @Table annotation didn't set the table name, assume class name
        if (model.tableName == null) {
            String className = modelFile.substring(modelFile.lastIndexOf("/") + 1);
            model.tableName = className.substring(0, className.indexOf("."));
        }
        model.tableName = model.tableName.toLowerCase().replaceAll("\"", "");

        return model;
    }

    /**
     * Use the awk script to get the custom types declared as TypeSerializer in the target file
     */
    private static Map<String, String> getCustomTypes(String file) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("awk", AWK_GET_SOURCE_DESTINATION_TYPES, file);
        builder.redirectErrorStream(true);
        Process p = builder.start();

        Map<String, String> types = new HashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while (true) {
            line = r.readLine();
            if (line == null) {
                break;
            }
            String[] typeInfo = line.split(" ");
            if (typeInfo.length == 2) {
                types.put(typeInfo[0], typeInfo[1]);
            }
        }
        return types;
    }

    /**
     * Use grep to see all the files that contain TypeSerializer, and collect them all
     */
    private static Map<String, String> getCustomTypes() throws IOException {
        String line;
        Map<String, String> types = new HashMap<>();
        ProcessBuilder builder = new ProcessBuilder("grep", "-rlP", "extends\\s+TypeSerializer");
        builder.redirectErrorStream(true);
        Process p = builder.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));

        // This grep command will output only the file names of the files that contain a TypeSerializer
        while (true) {
            line = r.readLine();
            if (line == null) {
                break;
            }
            // Add all the custom types contained in that file
            types.putAll(getCustomTypes(line));
        }

        return types;
    }

    /**
     * Convert native or custom type to SQLite type
     */
    private static String getColumnType(Column field, Map<String, String> types) {
        String type;

        // Custom type? => get the DB type instead
        if (types.containsKey(field.type)) {
            type = types.get(field.type);
        } else {
            type = field.type;
        }

        // type is either a native type or a foreign key
        switch (type) {
            case "Double":
            case "double":
            case "Float":
            case "float":
                return "REAL";

            case "Long":
            case "long":
            case "Integer":
            case "int":
            case "Boolean":
            case "boolean":
            case "Calendar":
                return "INTEGER";

            case "String":
                return "TEXT";

            default:
                if (field.fk != null) {
                    return "INTEGER REFERENCES " + field.fk + "(Id) ON DELETE " + field.delete + " ON UPDATE " + field.update;
                } else {
                    System.out.println("Unable to detect field type: " + field);
                }
                break;
        }

        return "";
    }

    /**
     * Convert a class information into an SQL statement
     */
    private static String computeCreateStatement(Model model, Map<String, String> types) throws IOException {
        String sql = "CREATE TABLE " + model.tableName + " (Id INTEGER PRIMARY KEY AUTOINCREMENT";
        for (Column field : model.fields) {
            sql += ", " + field.name + " " + getColumnType(field, types);

        }
        sql += ");";
        return sql;
    }

    /**
     * Description of a field annoted @Column
     * Safe to search in Collections thanks to hashCode and equals
     */
    private static class Column {
        String type;
        String name;
        public String fk = null;
        public String delete = "NO ACTION";
        public String update = "NO ACTION";

        public Column(String type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Column) || other == null) {
                return false;
            }
            Column c = (Column) other;
            if ((c.type == null && type != null) || (c.type != null && type == null)) {
                return false;
            }
            if ((c.name == null && name != null) || (c.name != null && name == null)) {
                return false;
            }
            return (type == null || type.equals(c.type)) && (name == null || name.equals(c.name));
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (type == null ? 0 : type.hashCode());
            result = 31 * result + (name == null ? 0 : name.hashCode());
            return result;
        }
        @Override
        public String toString() {
            return String.format("%s %s", type, name);
        }
    }
}

