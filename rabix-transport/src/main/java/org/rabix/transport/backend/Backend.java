package org.rabix.transport.backend;

import org.apache.commons.lang.StringUtils;
import org.rabix.transport.backend.impl.BackendActiveMQ;
import org.rabix.transport.backend.impl.BackendLocal;
import org.rabix.transport.backend.impl.BackendRabbitMQ;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ 
    @Type(value = BackendActiveMQ.class, name = "ACTIVE_MQ"),
    @Type(value = BackendRabbitMQ.class, name = "RABBIT_MQ"),
    @Type(value = BackendLocal.class, name = "LOCAL") })
@JsonInclude(Include.NON_NULL)
public abstract class Backend {

  @JsonProperty("id")
  protected UUID id;

  @JsonProperty("name")
  protected String name;


  public Backend() {
    this(UUID.randomUUID());
  }

  public Backend(UUID id) {
    this(id, id.toString());
  }

  public Backend(String name) {
    UUID id;
    try {
      id = UUID.fromString(name);
    } catch (Exception e) {
      id = UUID.randomUUID();
    }
    init(id, name);
  }

  public Backend(UUID id, String name) {
    init(id, name);
  }

  private void init(UUID id, String name) {
    this.id = id;
    if (StringUtils.isEmpty(name)) {
      name = id.toString();
    }
    this.name = name;
  }

  public static enum BackendType {
    LOCAL,
    ACTIVE_MQ,
    RABBIT_MQ
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public abstract BackendType getType();

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((id == null) ? 0 : id.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Backend other = (Backend) obj;
    if (id == null) {
      if (other.id != null)
        return false;
    } else if (!id.equals(other.id))
      return false;
    return true;
  }
  
}
