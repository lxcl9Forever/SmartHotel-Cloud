package com.smarthotel.integration.workflow;
import java.util.*;
public final class WorkflowContext {
 private static final ThreadLocal<List<WorkflowContract.Effect>> EFFECTS=new ThreadLocal<>();
 private static final ThreadLocal<String> ID=new ThreadLocal<>();
 private WorkflowContext(){}
 static void begin(String id){EFFECTS.set(new ArrayList<>());ID.set(id);}static void end(){EFFECTS.remove();ID.remove();}
 public static String operationId(){if(ID.get()==null)throw new IllegalStateException("Workflow required");return ID.get();}
 public static void effect(String kind,String until,Map<String,Object> values){var effects=EFFECTS.get();if(effects==null)throw new IllegalStateException("A durable workflow is required");effects.add(new WorkflowContract.Effect(kind,until,values));}
 static List<WorkflowContract.Effect> effects(){return List.copyOf(EFFECTS.get());}
}
