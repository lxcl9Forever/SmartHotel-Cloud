import java.nio.file.*;
class StatisticsUpgrade {
  public static void main(String[]a)throws Exception {
    var t=new DbSplit(Path.of(a[0]),a[1],13306);
    try {
      String b=t.env.get("BUSINESS_MYSQL_DATABASE");
      boolean missing=!t.tables(b).contains("hotel_daily_statistics");
      t.backup(b);
      t.execute("USE "+DbSplit.ident(b));
      String s=Files.readString(t.root.resolve("database/19_daily_statistics.sql")).replace("USE smart_hotel_cloud;","");
      t.execute(s);
      t.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(b,"hotel_daily_statistics")+" TO "+DbSplit.literal(t.env.get("BUSINESS_MYSQL_USER"))+"@'%'");
      t.report.setProperty("statistics.created",Boolean.toString(missing));
      t.saveReport("STATISTICS_READY");
      System.out.println("Statistics table missing before fix="+missing+"; backup="+t.output);
    }
    finally {
      t.admin.close();
    }
  }
}
