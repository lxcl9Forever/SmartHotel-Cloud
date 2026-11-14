package com.smarthotel.integration;
import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.time.Duration;
@Component public class InternalHttp {
  @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.cloud.client.loadbalancer.LoadBalancerClient balancer;
  private final RestClient client;
  private final String token;
  public InternalHttp(@Value("${smarthotel.internal.token}") String token) {
    this.token=token;
    var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    client=RestClient.builder().requestFactory(factory).build();
  }
  public <T>T post(String url,Object body,Class<T> type) {
    try {
      return client.post().uri(resolve(url)).header("X-Internal-Token",token).body(body).retrieve().body(type);
    }
    catch(RestClientResponseException e) {
      throw new BusinessException(e.getStatusCode().value(),reason(e,"远程业务操作被拒绝"));
    }
    catch(RuntimeException e) {
      throw new BusinessException(503,"服务通信中断，请查询原业务结果后重试");
    }
  }
  public <T>T get(String url,Class<T> type) {
    try {
      return client.get().uri(resolve(url)).header("X-Internal-Token",token).retrieve().body(type);
    }
    catch(RestClientResponseException e) {
      throw new BusinessException(e.getStatusCode().value(),reason(e,"远程业务查询失败"));
    }
    catch(RuntimeException e) {
      throw new BusinessException(503,"依赖服务暂不可用");
    }
  }
  String resolve(String url) {
    if(!url.startsWith("lb://"))return url;
    var address=java.net.URI.create(url);
    if(balancer==null)throw new BusinessException(503,"服务发现不可用");
    var instance=balancer.choose(address.getHost());
    if(instance==null)throw new BusinessException(503,"目标服务没有健康实例");
    try{return new java.net.URI(instance.isSecure()?"https":"http",null,instance.getHost(),instance.getPort(),address.getPath(),address.getQuery(),null).toString();}
    catch(java.net.URISyntaxException invalid){throw new BusinessException(503,"服务实例地址无效");}
  }
  private String reason(RestClientResponseException error,String fallback) {
    try {
      String message=new com.fasterxml.jackson.databind.ObjectMapper().readTree(error.getResponseBodyAsString()).path("message").asText("");
      return message.isBlank()||message.length()>200?fallback:message;
    }
    catch(Exception ignored) {
      return fallback;
    }
  }
}
