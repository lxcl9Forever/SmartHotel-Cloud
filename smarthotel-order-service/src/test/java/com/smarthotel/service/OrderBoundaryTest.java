package com.smarthotel.service;
import com.smarthotel.config.OrderBoundaryConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
class OrderBoundaryTest {
  final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(OrderBoundaryConfiguration.class);
  @Test void defaultAndExplicitRemoteModeAreAccepted() {
    runner.run(c->assertThat(c).hasNotFailed());
    runner.withPropertyValues("smarthotel.inventory.mode=remote").run(c->assertThat(c).hasNotFailed());
  }
  @Test void localAndUnknownModesAreRejected() {
    for(String mode:new String[] {
      "local","unknown"
    }
    )runner.withPropertyValues("smarthotel.inventory.mode="+mode).run(c->assertThat(c).hasFailed());
  }
  @Test void productionSourceCannotReadOrWriteInventoryTables()throws Exception {
    var sql=Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+(?:`?\\w+`?\\.)?`?(?:room_inventory|inventory_operation|inventory_cache_sync|inventory_reservation|inventory_tcc_attempt)\\b");
    assertThat(sql.matcher("SELECT * FROM room_inventory").find()).isTrue();
    assertThat(sql.matcher("SELECT * FROM inventory_tcc_journal").find()).isFalse();
    try(var files=Files.walk(Path.of("src/main/java"))) {
      for(var p:files.filter(f->f.toString().endsWith(".java")).toList()) {
        var body=Files.readString(p);
        assertThat(body).as(p.toString()).doesNotContain("RoomInventoryMapper","LocalInventoryCommands","LocalInventoryProjection");
        assertThat(sql.matcher(body).find()).as(p+" has cross-domain inventory SQL").isFalse();
      }
    }
  }
  @Test void legacyImplementationsAreExcludedFromProductionClasspath() {
    for(String name:new String[] {
      "com/smarthotel/service/LocalInventoryCommands.class","com/smarthotel/service/LocalInventoryProjection.class","com/smarthotel/mapper/RoomInventoryMapper.class"
    }
    )assertThat(Files.exists(Path.of("target/classes",name))).isFalse();
  }
}
