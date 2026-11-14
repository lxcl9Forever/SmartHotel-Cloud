import java.nio.file.*;
import java.sql.*;
import java.util.*;
class InventorySplit {
  static void copy(DbSplit t,String from,String to)throws Exception {
    t.createSchema(to);
    t.execute("SET FOREIGN_KEY_CHECKS=0");
    try {
      for(String table:DbSplit.INVENTORY_TABLES) {
        t.execute(t.qualify(t.ownedDdl(from,table,DbSplit.INVENTORY_TABLES),to,table));
        t.copyRows(from,to,table,null,false);
        t.compare(from,to,table,null);
      }
    }
    finally {
      t.execute("SET FOREIGN_KEY_CHECKS=1");
    }
    t.checkForeignKeys(to);
  }
  static void detach(DbSplit t,String schema)throws Exception {
    var rows=new ArrayList<String[]>();
    try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT DISTINCT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+DbSplit.literal(schema)+" AND REFERENCED_TABLE_NAME IS NOT NULL")) {
      while(rs.next())if(DbSplit.INVENTORY_TABLES.contains(rs.getString(1))!=DbSplit.INVENTORY_TABLES.contains(rs.getString(3)))rows.add(new String[] {
        rs.getString(1),rs.getString(2)
      }
      );
    }
    for(var row:rows)t.execute("ALTER TABLE "+DbSplit.full(schema,row[0])+" DROP FOREIGN KEY "+DbSplit.ident(row[1]));
    t.checkForeignKeys(schema);
  }
  static void audit(DbSplit t,String b,String i,String h)throws Exception {
    for(String q:List.of("SELECT COUNT(*) FROM "+DbSplit.full(b,"inventory_tcc_journal")+" WHERE state='PENDING'","SELECT COUNT(*) FROM "+DbSplit.full(i,"inventory_tcc_attempt")+" WHERE phase='PREPARED'","SELECT COUNT(*) FROM "+DbSplit.full(i,"room_inventory")+" WHERE locked_qty<0 OR sold_qty<0 OR locked_qty+sold_qty>total_qty","SELECT COUNT(*) FROM "+DbSplit.full(i,"inventory_reservation")+" r LEFT JOIN "+DbSplit.full(b,"booking_order")+" o ON r.order_id=o.order_id WHERE (o.order_id IS NULL AND (r.state IN ('RESERVED','CONFIRMED') OR r.pending_attempt_id IS NOT NULL)) OR (o.order_id IS NOT NULL AND (r.room_type_id<>o.room_type_id OR r.room_count<>o.room_count OR r.check_in_date<>o.check_in_date OR r.check_out_date<>o.check_out_date))","SELECT COUNT(*) FROM "+DbSplit.full(i,"room_inventory")+" i LEFT JOIN "+DbSplit.full(h,"room_type")+" h ON i.room_type_id=h.room_type_id WHERE h.room_type_id IS NULL"))if(t.number(q)!=0)throw new IllegalStateException("Outstanding decision or inconsistent inventory; resolve before migration");
  }
  public static void main(String[] args)throws Exception {
    var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
    try {
      t.stopRequired();
      if(t.env.containsKey("INVENTORY_DATA_LAYOUT"))throw new IllegalStateException("Inventory layout already set");
      if(!"split".equals(t.env.get("HOTEL_DATA_LAYOUT"))||!"remote".equals(t.env.get("INVENTORY_MODE")))throw new IllegalStateException("Complete hotel split and remote inventory first");
      String b=t.env.get("BUSINESS_MYSQL_DATABASE"),h=t.env.get("HOTEL_MYSQL_DATABASE"),u=t.env.get("USER_MYSQL_DATABASE"),target="smart_hotel_inventory";
      audit(t,b,b,h);
      Path backup=t.backup(b);
      t.backup(u);
      t.backup(h);
      Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-inventory-split.private"));
      var grants=new ArrayList<String>();
      for(String name:List.of(t.env.get("BUSINESS_MYSQL_USER"),t.env.get("INVENTORY_MYSQL_USER"))) {
        try(var st=t.admin.createStatement();var rs=st.executeQuery("SHOW GRANTS FOR "+DbSplit.literal(name)+"@'%'")) {
          while(rs.next())grants.add(rs.getString(1));
        }
      }
      Files.write(t.output.resolve("grants-before.sql"),grants);
      String suffix=UUID.randomUUID().toString().substring(0,8),rb="sh_istock_b_"+suffix,ri="sh_istock_i_"+suffix,merged="sh_istock_m_"+suffix;
      t.createSchema(rb);
      t.restore(backup,rb);
      for(String table:t.tables(b))t.compare(b,rb,table,null);
      copy(t,rb,ri);
      detach(t,rb);
      t.execute("UPDATE "+DbSplit.full(ri,"room_inventory")+" SET version=version+1 ORDER BY inventory_id LIMIT 1");
      t.execute("INSERT INTO "+DbSplit.full(rb,"inventory_tcc_journal")+"(attempt_id,order_id,payload_json) VALUES('inventory-split-rehearsal',1,'{}')");
      t.mergeWithInventory(t.source,u,rb,h,ri,merged);
      for(String table:DbSplit.INVENTORY_TABLES)t.compare(ri,merged,table,null);
      for(String table:t.tables(rb))if(!DbSplit.INVENTORY_TABLES.contains(table)&&!DbSplit.HOTEL_TABLES.contains(table)&&!table.equals(DbSplit.AUDIT))t.compare(rb,merged,table,null);
      for(String db:List.of(merged,ri,rb))t.execute("DROP DATABASE "+DbSplit.ident(db));
      t.report.setProperty("inventory.rollback","latest inventory version and pending business journal preserved");
      t.saveReport("INVENTORY_SPLIT_REHEARSED");
      copy(t,b,target);
      t.backup(target);
      audit(t,b,target,h);
      detach(t,b);
      var owner=t.createPrincipal("sh_inventory_domain",target,DbSplit.INVENTORY_TABLES);
      var rows=new ArrayList<String[]>();
      try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT User,Host,Table_name FROM mysql.tables_priv WHERE Db="+DbSplit.literal(b))) {
        while(rs.next())if(DbSplit.INVENTORY_TABLES.contains(rs.getString(3)))rows.add(new String[] {
          rs.getString(1),rs.getString(2),rs.getString(3)
        }
        );
      }
      for(var row:rows)t.execute("REVOKE ALL PRIVILEGES ON "+DbSplit.full(b,row[2])+" FROM "+DbSplit.literal(row[0])+"@"+DbSplit.literal(row[1]));
      try(var db=t.connect(target,owner.user(),owner.password())) {
        for(String table:DbSplit.INVENTORY_TABLES)t.number("SELECT COUNT(*) FROM "+DbSplit.full(target,table));
        t.denied(db,"SELECT * FROM "+DbSplit.full(b,"booking_order")+" LIMIT 0");
        t.denied(db,"SELECT * FROM "+DbSplit.full(b,"inventory_tcc_journal")+" LIMIT 0");
        t.denied(db,"SELECT * FROM "+DbSplit.full(h,"room_type")+" LIMIT 0");
      }
      try(var db=t.connect(b,t.env.get("BUSINESS_MYSQL_USER"),t.env.get("BUSINESS_MYSQL_PASSWORD"))) {
        for(String table:DbSplit.INVENTORY_TABLES) {
          t.denied(db,"SELECT * FROM "+DbSplit.full(target,table)+" LIMIT 0");
          t.denied(db,"SELECT * FROM "+DbSplit.full(b,table)+" LIMIT 0");
        }
      }
      t.execute("USE "+DbSplit.ident(b));
      t.execute(Files.readString(t.root.resolve("database/19_daily_statistics.sql")).replace("USE smart_hotel_cloud;",""));
      t.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(b,"hotel_daily_statistics")+" TO "+DbSplit.literal(t.env.get("BUSINESS_MYSQL_USER"))+"@'%'");
      var updates=Map.of("INVENTORY_DATA_LAYOUT","split","INVENTORY_MYSQL_DATABASE",target,"INVENTORY_MYSQL_USER",owner.user(),"INVENTORY_MYSQL_PASSWORD",owner.password());
      var lines=new ArrayList<String>();
      for(String line:Files.readAllLines(t.root.resolve(".env"))) {
        int n=line.indexOf('=');
        if(n<0||!updates.containsKey(line.substring(0,n)))lines.add(line);
      }
      updates.forEach((k,v)->lines.add(k+"="+v));
      Path tmp=t.root.resolve(".env.stock-next");
      Files.write(tmp,lines);
      Files.move(tmp,t.root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
      t.saveReport("INVENTORY_SPLIT_READY");
      System.out.println("Inventory split complete; private backup: "+t.output);
    }
    catch(Exception e) {
      t.saveReport("FAILED");
      throw e;
    }
    finally {
      t.admin.close();
    }
  }
}
