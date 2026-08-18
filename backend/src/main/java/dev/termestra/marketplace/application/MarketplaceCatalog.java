package dev.termestra.marketplace.application;
import java.util.Map;
public interface MarketplaceCatalog {Map<String,Object> manifest(String language);AgentDetail agent(String language,String path);record AgentDetail(String path,Map<String,Object> frontmatter,String body){}}
