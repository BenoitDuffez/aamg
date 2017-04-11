import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Representation of a model class
 */
class Model {
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
    Model(String sha1, String modelFile) throws IOException {
        if (!modelFile.endsWith("/com/activeandroid/Model.java")
                && !modelFile.endsWith("/java/util/Calendar.java")
                && !(new File(modelFile).exists())) {
            throw new IllegalArgumentException("Unable to open model file: " + modelFile);
        }

        // Various regexes used to parse the interesting sections of the file
        Pattern column = Pattern.compile("(public)?\\s*([^ ]+)\\s*([^\\s]+)\\s*;");

        Pattern table = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*([^\\)]+)\\)");
        Pattern pkg = Pattern.compile("package\\s+([^\\s;]+)\\s*;");
        Pattern imp = Pattern.compile("import\\s+([^\\s;]+)\\s*;");
        Pattern ext = Pattern.compile("extends\\s+([^\\s\\{]+)\\s*\\{?");
        Pattern keys = Pattern.compile("on(Delete|Update)\\s*=\\s*.*(SET_NULL|SET_DEFAULT|CASCADE|RESTRICT|NO_ACTION)");

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
                    Model parent = new Model(sha1, packagePath + imports.get(subclass.group(1)) + ".java");
                    fields.addAll(parent.fields);
                }
            }

            // See if the @Table annotation contains the table name
            if (tableName == null) {
                Matcher tableMatcher = table.matcher(line);
                if (tableMatcher.find()) {
                    tableName = tableMatcher.group(1);
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

                        // Try to detect FK
                        switch (colMatcher.group(2)) {
                            case "Boolean": case "boolean":
                            case "Integer": case "int":
                            case "Double":  case "double":
                            case "Float":   case "float":
                            case "char":    case "CharSequence":    case "String":
                                // no-op
                                break;

                            default:
                                // If the column type is imported and is a model, save the foreign key table name
                                if (imports.containsKey(colMatcher.group(2))) {
                                    col.fk = new Model(sha1, packagePath + imports.get(colMatcher.group(2)) + ".java").tableName;
                                }
                                // try package local
                                else if (col.fk == null) {
                                    col.fk = new Model(sha1, packagePath + colMatcher.group(2) + ".java").tableName;
                                }
                                break;
                        }

                        fields.add(col);
                        break;
                    }
                } while ((line = r.readLine()) != null);
            }
        }

        // If the @Table annotation didn't set the table name, assume class name
        if (tableName == null) {
            String className = modelFile.substring(modelFile.lastIndexOf("/") + 1);
            tableName = className.substring(0, className.indexOf("."));
        }
        tableName = tableName.toLowerCase().replaceAll("\"", "");
    }
}

