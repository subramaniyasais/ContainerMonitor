package ContainerMonitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
@author subra
 */
public class ContainerMonitor {

	JSONObject json;
	String[] container;
	int container_count = 0;
	String container_id[];
	String container_Name[];
	String container_creation_time[];
	int memory_usage[];
	String memory_limit[];
	String memory_reservation[];
	String memory_swap_limit[];
	JSONArray container_stats[];
	int maxMemory = 1951;
	int usedMemory = 0;
	int freeMemory = 0;

	final int MAX_THRESHOLD = 90;
	final int MIN_THRESHOLD = 70;
	final int SWAP_LIMIT = 10;

	public ContainerMonitor(String url) throws InterruptedException {

		try {
			readJsonFromUrl(url);
			parseJSONdata();
			printParsedJSON();
			manage_containers();
		} catch (IOException ex) {
			Logger.getLogger(ContainerMonitor.class.getName()).log(Level.SEVERE, null, ex);
		} catch (JSONException ex) {
			Logger.getLogger(ContainerMonitor.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	//Intialize all the variables to store each containers json info
	public void initializeData() {
		container_id = new String[container_count];
		container_Name = new String[container_count];
		container_creation_time = new String[container_count];
		memory_limit = new String[container_count];
		memory_reservation = new String[container_count];
		memory_swap_limit = new String[container_count];
		container_stats = new JSONArray[container_count];
		memory_usage = new int[container_count];
	}

	//Parse the JSON data and obtain the required values.
	public void parseJSONdata() throws JSONException {
		container = JSONObject.getNames(json);
		container_count = container.length;

		initializeData();
		for (int i = 0; i < container_count; i++) {
			container_id[i] = (json.getJSONObject(container[i]).get("id")).toString();
			container_Name[i] = json.getJSONObject(container[i]).getJSONArray("aliases").getString(0);

			JSONObject specObj = (json.getJSONObject(container[i])).getJSONObject("spec");
			container_creation_time[i] = specObj.getString("creation_time").toString();
			if (specObj.get("has_memory").toString() == "true") {
				memory_limit[i] = specObj.getJSONObject("memory").get("limit").toString();
				memory_reservation[i] = specObj.getJSONObject("memory").get("reservation").toString();
			}
			container_stats[i] = (json.getJSONObject(container[i]).getJSONArray("stats"));

		}

	}
	//Parse the JSON data and obtain the required values.
	public void printParsedJSON() throws JSONException, FileNotFoundException {
		PrintWriter writer = new PrintWriter("container_stats.txt");
		for (int i = 0; i < container_count; i++) {
			memory_usage[i] = 0;
			printout(writer, "id:" + container_id[i]);
			printout(writer, "name:" + container_Name[i]);
			printout(writer, "creation_time:" + container_creation_time[i]);
			printout(writer, "memory_limit:" + memory_limit[i]);
			printout(writer, "memory_reservaion:" + memory_reservation[i]);
			for (int s = container_stats[i].length() - 1, limit = 0; (s > 0 && limit < 4); s--, limit++) {
				printout(writer, limit + "-Time:" + container_stats[i].getJSONObject(s).get("timestamp"));
				printout(writer, limit + "-usage_total:" + container_stats[i].getJSONObject(s).getJSONObject("cpu").getJSONObject("usage").get("total"));
				printout(writer, limit + "-per_cpu_usage:" + container_stats[i].getJSONObject(s).getJSONObject("cpu").getJSONObject("usage").get("per_cpu_usage").toString());
				printout(writer, limit + "-memory_usage:" + container_stats[i].getJSONObject(s).getJSONObject("memory").get("usage"));
				//Get the memory info and calculate the memory usage
				Long temp = (Long.parseLong(container_stats[i].getJSONObject(s).getJSONObject("memory").get("usage").toString()))/1024L;
				int t = (int) (long) (temp/1024);
				if(memory_usage[i] < t) {
					memory_usage[i] = t;
				}
			}
			printout(writer, "###");
		}
		printout(writer, "###END");
		writer.close();

	}

	//Manage container's memory
	public void manage_containers() throws IOException, InterruptedException {

		for( int l=0 ;l<container_count; l++) {

			System.out.println("container "+(l+1)+": "+memory_usage[l] + " MB");

		}

		System.out.println("Total Memory: "+maxMemory + " MB");
		usedMemory = 0;
		int hoggingContainer = -1;
		int hoggingContainerMem = 0;
		
		//Calculate which is the most hogging container
		for( int l=0 ;l<container_count; l++) {
			memory_usage[l] = (int)((memory_usage[l] * 100.0f) / maxMemory);
			usedMemory += memory_usage[l];
			System.out.println("container "+(l+1)+": "+ memory_usage[l] + "%");
			if(hoggingContainerMem < memory_usage[l]) {
				hoggingContainerMem = memory_usage[l];
				hoggingContainer = l;
			}
		}
		System.out.println("-------------");
		System.out.println("Usage : "+usedMemory+"%");
		System.out.println("-------------");
		
		//Check if the total usage is more than the max threshold and limit the container
		if(usedMemory > MAX_THRESHOLD) {
			System.out.println("RAM usage above "+MAX_THRESHOLD+"%");
			int newMem = memory_usage[hoggingContainer]-(memory_usage[hoggingContainer]/100);
			newMem = (maxMemory*newMem)/100;
			//docker update -m 15942M ubuntu_test
			executeCommand("docker update -m "+newMem+"M "+container_Name[hoggingContainer]);
		}
		else {
			
			//Reset to full limits if total usage goes below the min threshold
			if(usedMemory < MIN_THRESHOLD){
				System.out.println("RAM usage is less than "+MIN_THRESHOLD+"%");
				System.out.println("In Safe State. Resetting the Container Limits");
				String cName = "";
				for(int c=0;c<container_count;c++){
					cName = cName.concat(container_Name[c]+" ");
				}
				//set the container with new limit
				executeCommand("docker update -m "+maxMemory+"M "+cName);
			}
			else{
				System.out.println("RAM usage is inbetween "+MIN_THRESHOLD+"% - "+MAX_THRESHOLD+"%");
				System.out.println("In a safe state");
			}
		}


	}

	private String readAll(Reader rd) throws IOException {
		StringBuilder sb = new StringBuilder();
		int cp;
		while ((cp = rd.read()) != -1) {
			sb.append((char) cp);
		}
		return sb.toString();
	}

	//Reads JSON data from the API
	public void readJsonFromUrl(String url) throws IOException, JSONException {
		InputStream is = new URL(url).openStream();
		try {
			BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
			String jsonText = readAll(rd);
			json = new JSONObject(jsonText);
		} finally {
			is.close();
		}
	}

	//Print to file and console.
	public void printout(PrintWriter writer, String data) {
		System.out.println(data);
		writer.println(data);
	}

	//Method to execute shell commands
	public BufferedReader executeCommand(String shellCommand) throws IOException, InterruptedException {

		Runtime runtime = Runtime.getRuntime();
		Process shellProcess = runtime.exec("/usr/local/bin/"+shellCommand);

		shellProcess.waitFor();

		return new BufferedReader(new InputStreamReader(shellProcess.getInputStream()));

	}

	//Calls the API every 3 seconds
	public static void main(String[] args) throws IOException, JSONException, InterruptedException {
		while(true){
			new ContainerMonitor("http://localhost:8080/api/v1.2/docker");
			Thread.sleep(3000);
		}


	}
}