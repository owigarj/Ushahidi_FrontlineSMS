package com.ushahidi.plugins.mapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import org.apache.log4j.Logger;

import com.ushahidi.plugins.mapping.data.domain.Category;
import com.ushahidi.plugins.mapping.data.domain.Location;
import com.ushahidi.plugins.mapping.data.domain.Incident;

import net.frontlinesms.Utils;

public class SynchronizationTask implements Runnable{
	/** The type of synchronization to be performed; pull or push */
	private String synchronizationType;
	
	/** The base task to be performed by the API; that which requires no extra parameters*/
	private String baseTask;
	
	/** Buffer to store extra task parameters to be passed along in the request */
	private StringBuffer taskBuffer;
	
	/** Instance of the SynchronizationManager that spawned this thread */
	private SynchronizationManager syncManager;
	
	/** List of values url parameter values */
	private List<String> taskValues = null;
	
	/** URL parameter value appendend at the end of the final url to the submitted*/
	private String urlParameterValue = null;
	
	/** Target URL for the synchronisation */
	private final String baseURL;
	
	private static Logger LOG = Utils.getLogger(SynchronizationTask.class);
	
	/**
	 * Creates an instance of the {@link SynchronizationTask}
	 * 
	 * @param syncManager Reference to the SynchronizationManger instance
	 * @param sourceURL Location with which to synchronise
	 * @param syncType Type of synchronisation; Can be either a push or pull task
	 * @param baseTask Task to be performed by this thread 
	 */
	public SynchronizationTask(SynchronizationManager syncManager, String sourceURL, String syncType, 
			String baseTask){
		this.synchronizationType = syncType;
		this.baseTask = baseTask;
		this.syncManager = syncManager;
		this.baseURL = sourceURL;
		
		//initialize the task buffer and store the initial request
		taskBuffer = new StringBuffer();
		taskBuffer.append(baseTask);
	}
	
	/**
	 * Creates an instance of {@link SynchronizationTask} @param taskValues results in more than
	 * one request being sent to the the Ushahidi API
	 * 
	 * @param syncManager Reference to the synchronisation manager
	 * @param syncURL URL for the synchronisation activity
	 * @param syncType Type of of synchronisation to be done
	 * @param baseTask Base task to be performed @see {@link SynchronizationAPI}
	 * @param taskValues List of values to be passed to the URL being submitted to the frontend 
	 */
	public SynchronizationTask(SynchronizationManager syncManager, String syncURL, String syncType,
			String baseTask, List<String> taskValues){
		
		this.syncManager = syncManager;
		this.synchronizationType = syncType;
		this.baseTask = baseTask;
		this.baseURL = syncURL;
		this.taskValues = (taskValues == null)? null : taskValues;
		
		//initialise the task buffer
		taskBuffer = new StringBuffer();
		taskBuffer.append(baseTask);
	}
	
	/**
	 * Adds extra request parameters for fetching info through the Ushahidi API
	 * @param parameter The extra request parameter
	 */
	public void addRequestParameter(String parameter){
		taskBuffer.append(parameter);
	}
	
	public void run(){
		if(synchronizationType.equalsIgnoreCase(SynchronizationAPI.PULL_TASK)){
			if(taskValues == null) performPullTask(); else multiplePullTask();
		}else if(synchronizationType.equalsIgnoreCase(SynchronizationAPI.PUSH_TASK)){
			performPushTask();
		}
	}
	
	/**
	 * Performs a pull task from an Ushahidi instance; fetches information
	 * 
	 */
	public void performPullTask(){
		String urlStr = getRequestURL();
		urlStr += (urlParameterValue == null)? "":urlParameterValue;
		StringBuffer buffer = new StringBuffer();
		try{
			String line = null;
			URL url = new URL(urlStr);			
			BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
			while((line = reader.readLine()) != null){
				buffer.append(line);
			}
			reader.close();
			processPayload(buffer.toString());
		}catch(MalformedURLException mex){
			LOG.debug("Invalid url ", mex);
		}catch(IOException iox){
			LOG.debug("Error in fetching response data", iox);
		}catch(JSONException jsx){
			LOG.debug("JSON Error ", jsx);
		}
	}
	
	/** Performs a pull task for each of the values in {@link #taskValues} */
	private void multiplePullTask(){
		for(String value: taskValues){
			urlParameterValue = value;
			performPullTask();
		}
		urlParameterValue = null;
	}
	
	/**
	 * Pushes information to the Ushahidi instance. Only the items that have been
	 * marked for posting are submitted
	 */
	public void performPushTask(){
		String urlParameterStr = SynchronizationAPI.REQUEST_URL_PREFIX + taskBuffer.toString();
		if(baseTask.equalsIgnoreCase(SynchronizationAPI.POST_INCIDENT)){
			urlParameterStr += SynchronizationAPI.getSubmitURLParameters(baseTask);
			//Fetch all incidents and post them one by one
			for(Incident incident: syncManager.getPendingIncidents())
				postIncident(incident, urlParameterStr);
			
		}else if(baseTask.equalsIgnoreCase(SynchronizationAPI.TAG_NEWS)){
			urlParameterStr += SynchronizationAPI.getSubmitURLParameters(baseTask);
		}
	}
	
	/**
	 * Gets the complete request URI to be submitted to the Ushahidi API
	 * @return 
	 */
	public String getRequestURL(){
		String extra = (baseURL.charAt(baseURL.length()-1) == '/')? "" : "/";
		return (baseTask == SynchronizationAPI.POST_INCIDENT)? baseURL + extra : 
			baseURL + extra + SynchronizationAPI.REQUEST_URL_PREFIX + taskBuffer.toString();
	}
	
	/**
	 * Gets the key to be used to lookup values in the payload
	 * @return
	 */
	public String getPayloadKey(){
		return SynchronizationAPI.getParameterKey(baseTask);
	}
	
	/**
	 * Processes the payload; JSON string returned by the http request
	 * @param payload
	 * @throws JSONException
	 */
	private void processPayload(String payload) throws JSONException{
		JSONObject jsonPayload = new JSONObject(payload);
		JSONObject data = jsonPayload.getJSONObject(SynchronizationAPI.PAYLOAD);
		
		String key = getPayloadKey();
		JSONArray items = data.getJSONArray(baseTask);
		for(int i=0; i<items.length(); i++){
			JSONObject item = (JSONObject)items.getJSONObject(i).get(key);
			if(baseTask.equals(SynchronizationAPI.CATEGORIES)){
				fetchCategory(item);
			}else if(baseTask.equals(SynchronizationAPI.INCIDENTS)){
				fetchIncident(item);
			}else if(baseTask.equals(SynchronizationAPI.LOCATIONS)){
				fetchLocation(item);
			}
		}
	}
	
	/**
	 * Fetches the categories from the JSON array and populates the InMemory database
	 * @param categories
	 * @throws JSONException
	 */
	private void fetchCategory(JSONObject item) throws JSONException{
		Category category = new Category();
		category.setFrontendId(item.getLong("id"));
		category.setTitle(item.getString("title"));
		category.setDescription(item.getString("description"));
		syncManager.addCategory(category);
	}
	
	private void fetchIncident(JSONObject item) throws JSONException{
		Incident incident = new Incident();
		incident.setFrontendId(item.getLong("incidentid"));
		incident.setTitle(item.getString("incidenttitle"));
		incident.setDescription(item.getString("incidentdescription"));
		
		//Fetch the location info
		Location location = new Location();
		location.setFrontendId(item.getLong("locationid"));
		location.setName(item.getString("locationname"));
		location.setLatitude(item.getDouble("locationlatitude"));
		location.setLongitude(item.getDouble("locationlongitude"));
		incident.setLocation(location);
		incident.setMarked(false);
		
		String dateStr = item.getString("incidentdate");;
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		try{
			Date date = dateFormat.parse(dateStr);
			incident.setIncidentDate(date);
		}catch(ParseException e){
			LOG.debug("Error in parsing the date",e);
		}
		syncManager.addIncident(incident);
	}
	
	private void fetchLocation(JSONObject item) throws JSONException{
		Location location = new Location();
		location.setFrontendId(item.getInt("id"));
		location.setName(item.getString("name"));
		location.setLatitude(item.getDouble("latitude"));
		location.setLongitude(item.getDouble("longitude"));
		syncManager.addLocation(location);

	}
	
	/**
	 * Posts an incident to the frontend. 
	 * 
	 * @param incident The incident to be posted
	 * @param urlParameterStr Pre-defined url parmaeter string for posting an incident to the frontend
	 */
	private void postIncident(Incident incident, String urlParameterStr){
		Date date = incident.getIncidentDate();
		String parameterStr = String.format(urlParameterStr, 
				incident.getTitle(), 
				incident.getDescription(), 
				getDateTimeComponent(date, "MM/dd/yyyy"), 
				getDateTimeComponent(date, "HH"), getDateTimeComponent(date, "mm"),
				getDateTimeComponent(date, "a").toLowerCase(),
				incident.getCategory().getFrontendId(),
				Double.toString(incident.getLocation().getLatitude()), 
				Double.toString(incident.getLocation().getLongitude()),
				incident.getLocation().getName()
				);
		//post the incident to the frontend
		LOG.debug("Posting incident "+parameterStr);
		//System.out.println(getRequestURL() + parameterStr);
		try{
			//Send data
			URL url = new URL(getRequestURL());			
			URLConnection conn = url.openConnection();
			conn.setDoOutput(true);
			OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
		
			//write parameters
			writer.write(parameterStr);
			writer.flush();
			
			//Get the response
			BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String line = null;
			StringBuffer sb = new StringBuffer();
			while((line = reader.readLine()) != null){
				sb.append(line);
			}

			//System.out.println(sb.toString());
			
			reader.close();
			writer.close();
						
			//Get the status of the posting and update the sync manager with the list of failed incidents
			JSONObject payload = new JSONObject(sb.toString());
			JSONObject status = payload.getJSONObject("error");			
			if(((String)status.get("code")).equalsIgnoreCase("0")){
				syncManager.updatePostedIncidents(incident);
				LOG.debug("Incident post succeeded: " + payload);
			}else{
				syncManager.updateFailedIncidents(incident);
				LOG.debug("Incident post failed: "+ payload.toString());
			}
			
		}catch(MalformedURLException me){
			LOG.debug("URL error: ", me);
		}catch(IOException io){
			LOG.debug("IO Error: ", io); 
			//io.printStackTrace();
		}catch(JSONException jsx){
			LOG.debug("JSON Error: ", jsx);
		}
	}
	
	private String getDateTimeComponent(Date date, String part){
		SimpleDateFormat dateFormat = new SimpleDateFormat(part);
		return dateFormat.format(date);
	}
	
}