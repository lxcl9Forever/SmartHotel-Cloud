package com.smarthotel.integration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.sql.*;
/** Identifiers originate from checked-in DomainOwnership, never from a caller's SQL. */
@Component public class RowStore {
  private final JdbcTemplate jdbc;
  public RowStore(JdbcTemplate jdbc) {
    this.jdbc=jdbc;
  }
  public String id(String name) {
    if(name==null||!name.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Invalid schema identifier");
    return "`"+name+"`";
  }
  public List<Map<String,Object>> rows(String table) {
    return jdbc.query("SELECT * FROM "+id(table),(rs,n)->row(rs));
  }
  public Map<String,Object> row(ResultSet rs)throws SQLException {
    var result=new LinkedHashMap<String,Object>();
    var md=rs.getMetaData();
    for(int i=1;i<=md.getColumnCount();i++) {
      Object v=rs.getObject(i);
      if(v instanceof Timestamp t)v=t.toLocalDateTime().toString();
      else if(v instanceof java.sql.Date d)v=d.toLocalDate().toString();
      else if(v instanceof byte[] b)v=Base64.getEncoder().encodeToString(b);
      result.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),v);
    }
    return result;
  }
  public String primaryKey(String table) {
    return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)connection-> {
      try(var rs=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),connection.getSchema(),table)) {
        String key=null;while(rs.next()) {
          if(key!=null)throw new IllegalArgumentException("Composite projection key is unsupported");key=rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
        }
        if(key==null)throw new IllegalArgumentException("Projection requires a primary key");return key;
      }
    }
    );
  }
  public Set<String> columns(String table) {
    return new HashSet<>(jdbc.query("SELECT * FROM "+id(table)+" WHERE 1=0",rs-> {
      var out=new ArrayList<String>();var md=rs.getMetaData();for(int i=1;i<=md.getColumnCount();i++)out.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));return out;
    }
    ));
  }
  public void upsert(String table,String primary,Map<String,Object> values) {
    var allowed=columns(table);
    var keys=values.keySet().stream().filter(allowed::contains).toList();
    if(!keys.contains(primary))throw new IllegalArgumentException("Missing projection primary key");
    String cols=String.join(",",keys.stream().map(this::id).toList());
    String marks=String.join(",",Collections.nCopies(keys.size(),"?"));
    String updates=String.join(",",keys.stream().filter(k->!k.equals(primary)).map(k->id(k)+"=VALUES("+id(k)+")").toList());
    if(updates.isEmpty())updates=id(primary)+"=VALUES("+id(primary)+")";
    jdbc.update("INSERT INTO "+id(table)+" ("+cols+") VALUES ("+marks+") ON DUPLICATE KEY UPDATE "+updates,keys.stream().map(values::get).toArray());
  }
}
