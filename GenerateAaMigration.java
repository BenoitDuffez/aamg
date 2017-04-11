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

        // Get the list of custom types that there may exist in the model class (or its ancestors)
        Map<String, String> types = getCustomTypes();

        // Add the custom types provided by AA: https://github.com/pardom/ActiveAndroid/tree/master/src/com/activeandroid/serializer
        types.put("BigDecimal", "String");
        types.put("Calendar", "Long");
        types.put("File", "String");
        types.put("Date", "long");
        types.put("UUID", "String");
        types.put("Date", "long");

        // Get the model before and after migration
        Model fromModel, toModel;
        if (sha1 == null) {
            fromModel = new Model("HEAD", model.toString(), types);
            toModel = new Model(null, model.toString(), types);
        } else {
            fromModel = new Model(sha1 + "~1", model.toString(), types);
            toModel = new Model(sha1, model.toString(), types);
        }

        // Do the diff and print a migration script
        new Migration(fromModel, toModel, types).writeMigrationScript();
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
}

