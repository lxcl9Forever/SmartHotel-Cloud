package com.smarthotel.integration;
import java.util.Map;
/** Only the local domain may write owner tables. Projections are explicitly allow-listed. */
public interface DomainOwnership {
 String domain();
 Map<String,String> ownedTables();
 /** source-domain:source-table -> local projection table */
 Map<String,String> projections();
 Map<String,String> targets();
 default java.util.Set<String> audience(String table){return targets().keySet();}
}
