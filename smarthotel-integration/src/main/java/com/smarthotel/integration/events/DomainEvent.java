package com.smarthotel.integration.events;
import java.util.Map;
public record DomainEvent(String eventId,String source,String entityType,String aggregateId,String primaryKey,long version,boolean deleted,Map<String,Object> row) {}
