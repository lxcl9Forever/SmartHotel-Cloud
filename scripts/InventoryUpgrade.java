import java.nio.file.*;
import java.util.*;
/** Uses the existing private backup/restore verifier before adding only the inventory foundation tables. */
class InventoryUpgrade {
 public static void main(String[] args)throws Exception {
  DbSplit tool=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try {
   tool.stopRequired();if("remote".equals(tool.env.get("INVENTORY_MODE")))throw new IllegalStateException("Inventory owner already active; refusing to regrant business inventory write permissions");String schema=tool.env.get("BUSINESS_MYSQL_DATABASE");
   if(schema==null)throw new IllegalStateException("Complete user/database separation first");
   DbSplit.ident(schema);
   if(tool.number("SELECT COUNT(*) FROM "+DbSplit.full(schema,"room_inventory")+" WHERE locked_qty<0 OR sold_qty<0 OR total_qty<locked_qty+sold_qty")!=0)
    throw new IllegalStateException("Inventory bounds require reconciliation before upgrade");
   var originalTables=tool.tables(schema);Path backup=tool.backup(schema);
   String snapshot="sh_rehearsal_inventory_"+UUID.randomUUID().toString().substring(0,8);
   tool.createSchema(snapshot);tool.restore(backup,snapshot);
   for(String table:originalTables)tool.compare(schema,snapshot,table,null);
   tool.checkForeignKeys(snapshot);tool.report.setProperty("inventory.backupRestore","verified all existing tables");tool.saveReport("INVENTORY_BACKUP_VERIFIED");
   tool.execute("USE "+DbSplit.ident(schema));
   String sql=Files.readString(tool.root.resolve("database/17_inventory_operations.sql")).replace("USE smart_hotel_cloud;","");
   for(String statement:sql.split(";"))if(!statement.isBlank())tool.execute(statement);
   String app=tool.env.get("BUSINESS_MYSQL_USER");DbSplit.ident(app);
   for(String table:List.of("inventory_operation","inventory_cache_sync","booking_request")) {
    tool.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(schema,table)+" TO "+DbSplit.literal(app)+"@'%'");
   }
   for(String table:originalTables)tool.compare(snapshot,schema,table,null);
   tool.execute("INSERT INTO "+DbSplit.full(schema,"inventory_cache_sync")+" (room_type_id,inventory_date,state,revision) SELECT room_type_id,inventory_date,'PENDING',1 FROM "+DbSplit.full(schema,"room_inventory")+" ON DUPLICATE KEY UPDATE room_type_id=inventory_cache_sync.room_type_id");
   // Exercise rollback with the new tables populated only inside the isolated restored copy.
   tool.execute("USE "+DbSplit.ident(snapshot));
   for(String statement:sql.split(";"))if(!statement.isBlank())tool.execute(statement);
   long order=tool.number("SELECT MIN(order_id) FROM "+DbSplit.full(snapshot,"booking_order"));
   if(order>0) {
    tool.execute("INSERT INTO "+DbSplit.full(snapshot,"inventory_operation")+" (operation_key,order_id,operation_type,request_signature,state) VALUES('ROLLBACK_REHEARSAL',"+order+",'TEST','synthetic','DONE')");
    tool.execute("INSERT INTO "+DbSplit.full(snapshot,"booking_request")+" (customer_id,request_key,payload_hash,order_id) SELECT customer_id,'rollback_rehearsal','"+"0".repeat(64)+"',order_id FROM "+DbSplit.full(snapshot,"booking_order")+" WHERE order_id="+order);
   }
   tool.execute("INSERT INTO "+DbSplit.full(snapshot,"inventory_cache_sync")+" (room_type_id,inventory_date,state) SELECT room_type_id,inventory_date,'PENDING' FROM "+DbSplit.full(snapshot,"room_inventory")+" LIMIT 1 ON DUPLICATE KEY UPDATE state='PENDING'");
   String merged="sh_rehearsal_inventory_merge_"+UUID.randomUUID().toString().substring(0,8);
   tool.mergeCurrent(tool.source,tool.env.get("USER_MYSQL_DATABASE"),snapshot,merged);
   for(String table:List.of("inventory_operation","inventory_cache_sync","booking_request"))tool.compare(snapshot,merged,table,null);
   tool.report.setProperty("inventory.rollbackRehearsal",order>0?"new operation, request binding and pending projection preserved in merged schema":"empty-order schema and projection preserved in merged schema");
   tool.execute("DROP DATABASE "+DbSplit.ident(merged));
   tool.execute("DROP DATABASE "+DbSplit.ident(snapshot));
   tool.saveReport("INVENTORY_FOUNDATION_READY");
   System.out.println("Inventory migration 17 complete; existing data unchanged. Private backup/report: "+tool.output);
  }catch(Exception error){tool.saveReport("FAILED");throw error;}finally{tool.admin.close();}
 }
}
