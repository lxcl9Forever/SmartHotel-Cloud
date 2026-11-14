package com.smarthotel.service;
import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component public class MarketingJournalPool {
  private final HikariDataSource pool;
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  public MarketingJournalPool(@Value("${spring.datasource.url}")String url,@Value("${spring.datasource.username}")String user,@Value("${spring.datasource.password}")String password) {
    var c=new HikariConfig();
    c.setJdbcUrl(url);
    c.setUsername(user);
    c.setPassword(password);
    c.setMaximumPoolSize(3);
    c.setMinimumIdle(0);
    c.setPoolName("marketing-decision-journal");
    c.setConnectionTimeout(3000);
    c.setInitializationFailTimeout(-1);
    if(url.startsWith("jdbc:mysql:"))c.setConnectionInitSql("SET time_zone='+08:00'");
    pool=new HikariDataSource(c);
    jdbc=new JdbcTemplate(pool);
    tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
  }
  @PreDestroy public void close() {
    pool.close();
  }
}
