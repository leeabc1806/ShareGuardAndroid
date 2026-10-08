import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Tiny local-only psql substitute for the isolated DB test on PCs without psql.exe. */
public class LocalSql {
    public static void main(String[] args) throws Exception {
        String sql = args.length == 0 ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        try (var db = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55435/postgres", "postgres", "");
             Statement statement = db.createStatement()) {
            boolean hasResult = statement.execute(sql);
            String last = "";
            while (true) {
                if (hasResult) {
                    try (ResultSet rows = statement.getResultSet()) {
                        StringBuilder value = new StringBuilder();
                        while (rows.next()) {
                            if (value.length() > 0) value.append('\n');
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                                if (i > 1) value.append('|');
                                Object field = rows.getObject(i);
                                value.append(field instanceof Boolean ? ((Boolean) field ? "t" : "f")
                                        : field == null ? "" : field);
                            }
                        }
                        last = value.toString();
                    }
                }
                if (!statement.getMoreResults() && statement.getUpdateCount() == -1) break;
                hasResult = statement.getResultSet() != null;
            }
            System.out.print(last);
        }
    }
}
