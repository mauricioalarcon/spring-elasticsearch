package fr.pilato.spring.elasticsearch;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.open.OpenIndexResponse;
import org.elasticsearch.action.admin.indices.settings.UpdateSettingsRequestBuilder;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

/**
 * An abstract {@link FactoryBean} used to create an ElasticSearch
 * {@link Client}.
 * <p>
 * The lifecycle of the underlying {@link Client} instance is tied to the
 * lifecycle of the bean via the {@link #destroy()} method which calls
 * {@link Client#close()}
 * </p>
 * <p>
 * You can define properties for this bean that will help to auto create indexes
 * and types.
 * <br>
 * By default, the factory will load a es.properties file in the classloader. It will
 * contains all information needed for your client, e.g.: cluster.name
 * <br>
 * If you want to  modify the filename used for properties, just define the settingsFile property.
 * <p>
 * In the following example, we will create two indexes :
 * <ul>
 *   <li>twitter
 *   <li>rss
 * </ul>
 * twitter index will contain a type :
 * <ul>
 *   <li>tweet
 * </ul>
 * rss index will contain two types :
 * <ul>
 *   <li>feed
 *   <li>source
 * </ul>
 * Then we will define an alias alltheworld for twitter and rss indexes.
 * </p>
 * 
 * <pre>
 * {@code
 *  <bean id="esClient"
 *    class="fr.pilato.spring.elasticsearch.ElasticsearchTransportClientFactoryBean" >
 *    <property name="mappings">
 *      <list>
 *        <value>twitter/tweet</value>
 *        <value>rss/feed</value>
 *        <value>rss/source</value>
 *      </list>
 *    </property>
 *    <property name="aliases">
 *      <list>
 *        <value>alltheworld:twitter</value>
 *        <value>alltheworld:rss</value>
 *      </list>
 *    </property>
 *    <property name="templates">
 *      <list>
 *        <value>rss_template</value>
 *      </list>
 *    </property>    
 *    <property name="forceMapping" value="false" />
 *    <property name="mergeMapping" value="true" />
 *    <property name="forceTemplate" value="false" />    
 *    <property name="mergeSettings" value="true" />
 *    <property name="settingsFile" value="es.properties" />
 *  </bean>
 * }
 * </pre>
 * 
 * By default, indexes are created with their default Elasticsearch settings. You can specify 
 * your own settings for your index by putting a /es/indexname/_settings.json in your classpath.
 * <br>
 * So if you create a file named /es/twitter/_settings.json in your src/main/resources folder (for maven lovers),
 * it will be used by the factory to create the twitter index.
 * <pre>
 * {@code
 * {
 *   "index" : {
 *     "number_of_shards" : 3,
 *     "number_of_replicas" : 2
 *   }
 * }
 * </pre>
 * By default, types are not created and wait for the first document you send to Elasticsearch (auto mapping). 
 * But, if you define a file named /es/indexname/type.json in your classpath, the type will be created at startup using
 * the type definition you give.
 * <br>
 * So if you create a file named /es/twitter/tweet.json in your src/main/resources folder (for maven lovers),
 * it will be used by the factory to create the tweet type in twitter index.
 * <pre>
 * {@code
 * {
 *   "tweet" : {
 *     "properties" : {
 *       "message" : {"type" : "string", "store" : "yes"}
 *     }
 *   }
 * }
 * </pre>
 * 
 * @see {@link ElasticsearchTransportClientFactoryBean} to get a *simple*
 *      client.
 * @see {@link ElasticsearchClientFactoryBean} to get a client from a cluster
 *      node.
 * @author David Pilato
 */
public abstract class ElasticsearchAbstractClientFactoryBean extends ElasticsearchAbstractFactoryBean 
	implements FactoryBean<Client>,	InitializingBean, DisposableBean {

	protected final Log logger = LogFactory.getLog(getClass());

	protected Client client;

	protected boolean forceMapping;
	
	protected boolean forceTemplate;
	
	protected boolean mergeMapping;
	
	protected boolean mergeSettings;
	
	protected String[] mappings;

	protected String[] aliases;
	
	protected String[] templates;
	
	protected String classpathRoot = "/es";
	
	// TODO Let the user decide
	protected String jsonFileExtension = ".json";

	// TODO Let the user decide
	protected String indexSettingsFileName = "_settings.json";

	/**
	 * Implement this method to build a client
	 * @return ES Client
	 * @throws Exception if something goes wrong
	 */
	abstract protected Client buildClient() throws Exception;
	
	/**
	 * Set to true if you want to force reinit indexes/mapping
	 * @param forceMapping
	 */
	public void setForceMapping(boolean forceMapping) {
		this.forceMapping = forceMapping;
	}

	/**
	 * Set to true if you want to force recreate templates
	 * 
	 * @param forceTemplate
	 */
	public void setForceTemplate(boolean forceTemplate) {
		this.forceTemplate = forceTemplate;
	}
	
	/**
	 * Set to true if you want to try to merge mappings
	 * @param mergeMapping
	 */
	public void setMergeMapping(boolean mergeMapping) {
		this.mergeMapping = mergeMapping;
	}

	/**
	 * Set to true if you want to try to merge index settings
	 * @param mergeSettings
	 */
	public void setMergeSettings(boolean mergeSettings) {
		this.mergeSettings = mergeSettings;
	}
	
	/**
	 * Define mappings you want to manage with this factory
	 * <br/>use : indexname/mappingname form
	 * <p>Example :<br/>
 	 * <pre>
	 * {@code
	 * <property name="mappings">
     *  <list>
     *   <value>twitter/tweet</value>
     *   <value>rss/feed</value>
     *   <value>rss/source</value>
     *  </list>
     * </property>
	 * }
	 * </pre>
	 * @param mappings Array of indexname/mappingname
	 */
	public void setMappings(String[] mappings) {
		this.mappings = mappings;
	}

	/**
	 * Define mappings you want to manage with this factory
	 * <br/>use : aliasname:indexname form
	 * <p>Example :<br/>
 	 * <pre>
	 * {@code
	 * <property name="aliases">
     *  <list>
     *   <value>alltheworld:twitter</value>
     *   <value>alltheworld:rss</value>
     *  </list>
     * </property>
	 * }
	 * </pre>
	 * @param aliases alias array of aliasname:indexname
	 */
	public void setAliases(String[] aliases) {
		this.aliases = aliases;
	}

	/**
	 * Define templates you want to manage with this factory <br/>
	 * <p>
	 * Example :<br/>
	 * 
	 * <pre>
	 * {@code
	 * <property name="templates">
	 *  <list>
	 *   <value>template_1</value>
	 *   <value>template_2</value>
	 *  </list>
	 * </property>
	 * }
	 * </pre>
	 * 
	 * @param templates list of template
	 */
	public void setTemplates(String[] templates) {
		this.templates = templates;
	}
	
	/**
	 * Classpath root for index and mapping files (default : /es)
	 * <p>Example :<br/>
 	 * <pre>
	 * {@code
	 * <property name="classpathRoot" value="/es" />
	 * }
	 * </pre>
	 * That means that the factory will look in es folder to find index and mappings settings.
	 * <br/>So if you want to define a mapping for the tweet mapping in the twitter index, you
	 * should put a tweet.json file under /es/twitter/ folder.
	 * @param classpathRoot Classpath root for index and mapping files
	 * @see #setMappings(String[])
	 */
	public void setClasspathRoot(String classpathRoot) {
		this.classpathRoot = classpathRoot;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		logger.info("Starting ElasticSearch client");
		
		client = buildClient();
		initTemplates();		
		initMappings();
		initAliases();
	}

	@Override
	public void destroy() throws Exception {
		try {
			logger.info("Closing ElasticSearch client");
			if (client != null) {
				client.close();
			}
		} catch (final Exception e) {
			logger.error("Error closing ElasticSearch client: ", e);
		}
	}

	@Override
	public Client getObject() throws Exception {
		return client;
	}

	@Override
	public Class<Client> getObjectType() {
		return Client.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	/**
	 * Init templates if needed.
	 * <p>
	 * Note that you can force to recreate template using
	 * {@link #setForceTemplate(boolean)}
	 * 
	 * @throws Exception
	 */
	private void initTemplates() throws Exception {
		if (templates != null && templates.length > 0) {
			for (int i = 0; i < templates.length; i++) {
				String template = templates[i];
				Assert.hasText(template, "Can not read template in ["
						+ templates[i]
						+ "]. Check that templates is not empty.");
				createTemplate(template, forceTemplate);
			}
		}
	}
	
	/**
	 * Init mapping if needed.
	 * <p>Note that you can force to reinit mapping using {@link #setForceMapping(boolean)}
	 * @throws Exception 
	 */
	private void initMappings() throws Exception {
		// We extract indexes and mappings to manage from mappings definition
		if (mappings != null && mappings.length > 0) {
			Map<String, Collection<String>> indexes = new HashMap<String, Collection<String>>();
			
			for (int i = 0; i < mappings.length; i++) {
				String indexmapping = mappings[i];
				String[] indexmappingsplitted = indexmapping.split("/");
				String index = indexmappingsplitted[0];
				String mapping = indexmappingsplitted[1];
				
				if (index == null) throw new Exception("Can not read index in [" + indexmapping + 
						"]. Check that mappings contains only indexname/mappingname elements.");
				if (mapping == null) throw new Exception("Can not read mapping in [" + indexmapping + 
						"]. Check that mappings contains only indexname/mappingname elements.");

				// We add the mapping in the collection of its index
				if (!indexes.containsKey(index)) {
					indexes.put(index, new ArrayList<String>());
				}
				indexes.get(index).add(mapping);
			}
			
			// Let's initialize indexes and mappings if needed
			for (String index : indexes.keySet()) {
				if (!isIndexExist(index)) {
					createIndex(index);
				} else {
					if (mergeSettings) {
						mergeIndexSettings(index);
					}
				}
				
				Collection<String> mappings = indexes.get(index);
				for (Iterator<String> iterator = mappings.iterator(); iterator
						.hasNext();) {
					String type = iterator.next();
					pushMapping(index, type, forceMapping, mergeMapping);
				}
			}
		}
	}

	/**
	 * Init aliases if needed.
	 * @throws Exception 
	 */
	private void initAliases() throws Exception {

		if (aliases != null && aliases.length > 0) {
			for (int i = 0; i < aliases.length; i++) {
				String[] aliasessplitted = aliases[i].split(":");
				String alias = aliasessplitted[0];
				String index = aliasessplitted[1];
				
				if (index == null) throw new Exception("Can not read index in [" + aliases[i] + 
						"]. Check that aliases contains only aliasname:indexname elements.");
				if (alias == null) throw new Exception("Can not read mapping in [" + aliases[i] + 
						"]. Check that aliases contains only aliasname:indexname elements.");

				createAlias(alias, index);
			}
		}
	}

	/**
	 * Check if client is still here !
	 * @throws Exception
	 */
	private void checkClient() throws Exception {
		if (client == null) {
			throw new Exception("ElasticSearch client doesn't exist. Your factory is not properly initialized.");
		}
	}
	
	/**
	 * Create an alias if needed
	 * @param alias
	 * @param index
	 * @throws Exception
	 */
    private void createAlias(String alias, String index) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("createAlias("+alias+","+index+")");
		checkClient();
		
		IndicesAliasesResponse response = client.admin().indices().prepareAliases().addAlias(index, alias).execute().actionGet();
		if (!response.acknowledged()) throw new Exception("Could not define alias [" + alias + "] for index [" + index + "].");
		if (logger.isTraceEnabled()) logger.trace("/createAlias("+alias+","+index+")");
	}

	/**
	 * Create a template if needed
	 * 
	 * @param template template name
	 * @param force    force recreate template
	 * @throws Exception
	 */
	private void createTemplate(String template, boolean force)
			throws Exception {
		if (logger.isTraceEnabled())
			logger.trace("createTemplate(" + template + ")");
		checkClient();

		// If template already exists and if we are in force mode, we delete the
		// type and its mapping
		if (force && isTemplateExist(template)) {
			if (logger.isDebugEnabled())
				logger.debug("Force remove template [" + template + "]");
			// Remove template in ElasticSearch !
			final DeleteIndexTemplateResponse response = client.admin()
					.indices().prepareDeleteTemplate(template).execute()
					.actionGet();
		}

		// Read the template json file if exists and use it
		String source = readTemplate(template);
		if (source != null) {
			if (logger.isTraceEnabled())
				logger.trace("Template [" + template + "]=" + source);
			// Create template
			final PutIndexTemplateResponse response = client.admin().indices()
					.preparePutTemplate(template).setSource(source).execute()
					.actionGet();
			if (!response.acknowledged()) {
				throw new Exception("Could not define template [" + template
						+ "].");
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("Template [" + template
							+ "] successfully created.");
				}
			}
		} else {
			if (logger.isWarnEnabled()) {
				logger.warn("No template definition for [" + template
						+ "]. Ignoring.");
			}
		}

		if (logger.isTraceEnabled())
			logger.trace("/createTemplate(" + template + ")");
	}
    
	/**
	 * Check if an index already exists
	 * @param index Index name
	 * @return true if index already exists
	 * @throws Exception
	 */
    private boolean isIndexExist(String index) throws Exception {
		checkClient();
		return client.admin().indices().prepareExists(index).execute().actionGet().isExists();
	}
    
    /**
	 * Check if a mapping already exists in an index
	 * @param index Index name
	 * @param type Mapping name
	 * @return true if mapping exists
	 */
	private boolean isMappingExist(String index, String type) {
		ClusterState cs = client.admin().cluster().prepareState().setFilterIndices(index).execute().actionGet().getState();
		IndexMetaData imd = cs.getMetaData().index(index);
		
		if (imd == null) return false;

		MappingMetaData mdd = imd.mapping(type);

		if (mdd != null) return true;
		return false;
	}

	/**
	 * Check if a template already exists
	 * 
	 * @param template template name
	 * @return true if template exists
	 */
	private boolean isTemplateExist(String template) {
		ClusterState cs = client.admin().cluster().prepareState()
				.setFilterIndexTemplates(template).execute().actionGet()
				.getState();
		final IndexTemplateMetaData mdd = cs.getMetaData().templates()
				.get(template);

		if (mdd != null) return true;
		return false;
	}
	
	/**
	 * Define a type for a given index and if exists with its mapping definition
	 * @param index Index name
	 * @param type Type name
	 * @param force Force rebuild the type : <b>Caution</b> : if true, all your datas for
	 * this type will be erased. Use only for developpement or continuous integration
	 * @param merge Merge existing mappings
	 * @throws Exception
	 */
	private void pushMapping(String index, String type, boolean force, boolean merge) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("pushMapping("+index+","+type+","+force+")");

		checkClient();

		// If type already exists and if we are in force mode, we delete the type and its mapping
		if (force && isMappingExist(index, type)) {
			if (logger.isDebugEnabled()) logger.debug("Force remove old type and mapping ["+index+"]/["+type+"]");
			// Remove mapping and type in ElasticSearch !
			client.admin().indices()
				.prepareDeleteMapping(index)
				.setType(type)
				.execute().actionGet();
		}
		
		// If type does not exist, we create it
		boolean mappingExist = isMappingExist(index, type);
		if (merge || !mappingExist) {
			if (logger.isDebugEnabled()) {
				if (mappingExist) {
					logger.debug("Updating mapping ["+index+"]/["+type+"].");
				} else {
					logger.debug("Mapping ["+index+"]/["+type+"] doesn't exist. Creating it.");
				}
			}
			// Read the mapping json file if exists and use it
			String source = readMapping(index, type);
			if (source != null) {
				if (logger.isTraceEnabled()) logger.trace("Mapping for ["+index+"]/["+type+"]="+source);
				// Create type and mapping
				PutMappingResponse response = client.admin().indices()
					.preparePutMapping(index)
					.setType(type)
					.setSource(source)
					.execute().actionGet();			
				if (!response.acknowledged()) {
					throw new Exception("Could not define mapping for type ["+index+"]/["+type+"].");
				} else {
					if (logger.isDebugEnabled()) {
						if (mappingExist) {
							logger.debug("Mapping definition for ["+index+"]/["+type+"] succesfully merged.");
						} else {
							logger.debug("Mapping definition for ["+index+"]/["+type+"] succesfully created.");
						}
					}
				}
			} else {
				if (logger.isDebugEnabled()) logger.debug("No mapping definition for ["+index+"]/["+type+"]. Ignoring.");
			}
		} else {
			if (logger.isDebugEnabled()) logger.debug("Mapping ["+index+"]/["+type+"] already exists and mergeMapping is not set.");
		}
		if (logger.isTraceEnabled()) logger.trace("/pushMapping("+index+","+type+","+force+")");
	}

	/**
	 * Create a new index in Elasticsearch
	 * @param index Index name
	 * @throws Exception
	 */
	private void createIndex(String index) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("createIndex("+index+")");
		if (logger.isDebugEnabled()) logger.debug("Index " + index + " doesn't exist. Creating it.");
		
		checkClient();
		
		CreateIndexRequestBuilder cirb = client.admin().indices().prepareCreate(index);

		// If there are settings for this index, we use it. If not, using Elasticsearch defaults.
		String source = readIndexSettings(index);
		if (source != null) {
			if (logger.isTraceEnabled()) logger.trace("Found settings for index "+index+" : " + source);
			cirb.setSettings(source);
		}
		
		CreateIndexResponse createIndexResponse = cirb.execute().actionGet();
		if (!createIndexResponse.acknowledged()) throw new Exception("Could not create index ["+index+"].");
		if (logger.isTraceEnabled()) logger.trace("/createIndex("+index+")");
	}

	/**
	 * Create a new index in ElasticSearch
	 * @param index Index name
	 * @throws Exception
	 */
	private void mergeIndexSettings(String index) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("mergeIndexSettings("+index+")");
		if (logger.isDebugEnabled()) logger.debug("Index " + index + " already exists. Trying to merge settings.");
		
		checkClient();
		
		// Before merging, we have to close the index
//		CloseIndexRequestBuilder cirb = client.admin().indices().prepareClose(index);
//		CloseIndexResponse closeIndexResponse = cirb.execute().actionGet();
//		if (!closeIndexResponse.acknowledged()) throw new Exception("Could not close index ["+index+"].");

		UpdateSettingsRequestBuilder usrb = client.admin().indices().prepareUpdateSettings(index);

		// If there are settings for this index, we use it. If not, using Elasticsearch defaults.
		String source = readIndexSettings(index);
		if (source != null) {
			if (logger.isTraceEnabled()) logger.trace("Found settings for index "+index+" : " + source);
			usrb.setSettings(source);
		}
		
		usrb.execute().actionGet();
		
		OpenIndexRequestBuilder oirb = client.admin().indices().prepareOpen(index);
		OpenIndexResponse openIndexResponse = oirb.execute().actionGet();
		if (!openIndexResponse.acknowledged()) throw new Exception("Could not open index ["+index+"].");

		if (logger.isTraceEnabled()) logger.trace("/mergeIndexSettings("+index+")");
	}

	/**
	 * Read the mapping for a type.<br>
	 * Shortcut to readFileInClasspath(classpathRoot + "/" + index + "/" + mapping + jsonFileExtension);
	 * @param index Index name
	 * @param type Type name
	 * @return Mapping if exists. Null otherwise.
	 * @throws Exception
	 */
	private String readMapping(String index, String type) throws Exception {
		return readFileInClasspath(classpathRoot + "/" + index + "/" + type + jsonFileExtension);
	}	

	/**
	 * Read the template.<br>
	 * Shortcut to readFileInClasspath(classpathRoot + "/_template/" + template + jsonFileExtension);
	 * 
	 * @param template Template name
	 * @return Template if exists. Null otherwise.
	 * @throws Exception
	 */
	private String readTemplate(String template) throws Exception {
		return readFileInClasspath(classpathRoot + "/_template/" + template	+ jsonFileExtension);
	}
	
	/**
	 * Read settings for an index.<br>
	 * Shortcut to readFileInClasspath(classpathRoot + "/" + index + "/" + indexSettingsFileName);
	 * @param index Index name
	 * @return Settings if exists. Null otherwise.
	 * @throws Exception
	 */
	public String readIndexSettings(String index) throws Exception {
		return readFileInClasspath(classpathRoot + "/" + index + "/" + indexSettingsFileName);
	}	

	/**
	 * Read a file in classpath and return its content
	 * @param url File URL Example : /es/twitter/_settings.json
	 * @return File content or null if file doesn't exist
	 * @throws Exception
	 */
	public static String readFileInClasspath(String url) throws Exception {
		StringBuffer bufferJSON = new StringBuffer();
		
		try {
			InputStream ips= ElasticsearchAbstractClientFactoryBean.class.getResourceAsStream(url); 
			InputStreamReader ipsr = new InputStreamReader(ips);
			BufferedReader br = new BufferedReader(ipsr);
			String line;
			
			while ((line=br.readLine())!=null){
				bufferJSON.append(line);
			}
			br.close();
		} catch (Exception e){
			return null;
		}

		return bufferJSON.toString();
	}	

	
}
