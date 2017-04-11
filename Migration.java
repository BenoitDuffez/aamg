import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Actual migration script generator
 * Requires the from/to models and the list of custom types
 */
class Migration {
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
}

