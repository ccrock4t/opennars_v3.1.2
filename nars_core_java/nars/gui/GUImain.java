/* 
 * The MIT License
 *
 * Copyright 2019 The OpenNARS authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package nars.gui;

import com.google.gson.Gson;
import nars.entity.Concept;
import nars.entity.Task;
import nars.entity.TermLink;
import nars.language.Statement;
import nars.language.Term;
import nars.main.NAR;
import nars.storage.Buffer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * The main class of OpenNARS with support of a GUI. 
 * Alternatively it may start with shell only by invoking Shell.java. 
 * Manage the internal working thread. Communicate with NAR_GUI only.
 */
public class GUImain {

    NAR reasoner;
    /** NARS listen socket for GUI */
    ServerSocket serverSocket;
    /** String for the base URL */
    String baseURLstring = "http://127.0.0.1:" + Config.gui_port;
    /** queue shared between threads */
    List<String> pending_commands_list;

    public GUImain(NAR nar) {
        reasoner = nar;
        pending_commands_list = Collections.synchronizedList(new LinkedList<>());
        try {
            serverSocket = new ServerSocket(Config.NARS_listen_port);

            Runnable serverTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Socket clientSocket = serverSocket.accept();
                            try (BufferedReader br = new BufferedReader(
                                    new InputStreamReader(clientSocket.getInputStream(), "utf-8"))) {
                                StringBuilder data = new StringBuilder();
                                String responseLine = null;
                                while ((responseLine = br.readLine()) != null) {
                                    data.append(responseLine.trim());
                                }
                                System.out.println("Got input from GUI " + data);
                                pending_commands_list.add(data.toString());
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("Accept failed. " + e.toString());
                    }
                }
            };
            Thread t = new Thread(serverTask);
            t.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        HashMap<String, Object> initialize_jsonmap = new HashMap<>();
        initialize_jsonmap.put(APIKeys.KEY_NARS_NAME, reasoner.name);

        //channels and buffers
        ArrayList<HashMap<String, Object>> buffers = new ArrayList<>();

        HashMap<String, Object> globalBufferMap = new HashMap<>();
        globalBufferMap.put(APIKeys.KEY_BUFFER_NAME, nar.getGlobalBuffer().getName());
        globalBufferMap.put(APIKeys.KEY_BUFFER_CAPACITY, reasoner.getGlobalBuffer().capacity());

        HashMap<String, Object> internalBufferMap = new HashMap<>();
        internalBufferMap.put(APIKeys.KEY_BUFFER_NAME, nar.getInternalBuffer().getName());
        internalBufferMap.put(APIKeys.KEY_BUFFER_CAPACITY, reasoner.getInternalBuffer().capacity());

        buffers.add(globalBufferMap);
        buffers.add(internalBufferMap);

        initialize_jsonmap.put(APIKeys.KEY_BUFFERS, buffers);

        POST(initialize_jsonmap, APIKeys.PATH_INITIALIZE);
    }

    /**
     *  Handle requests from the GUI
     */
    public void ProcessSocket(){
        while(pending_commands_list.size() > 0){
            System.out.println("Taking input from shared list");
            String jsonString = pending_commands_list.remove(0);
            Gson gson = new Gson();
            Map<String, Object> jsonMap = gson.fromJson(jsonString, Map.class);

            String command = (String) jsonMap.get(APIKeys.COMMAND);
            Object data = jsonMap.get(APIKeys.KEY_DATA);
            System.out.println("jsonmap " + (String)data);
            if(command.equals(APIKeys.COMMAND_INPUT)){
                String[] lines = ((String) data).split("\n");
                for(String line : lines)
                    reasoner.textInputLine(line);
            }else if(command.equals(APIKeys.COMMAND_GET_CONCEPT_INFO)){
                Concept concept = reasoner.getMemory().getConcept(new Term((String) data));
                HashMap<String, Object> conceptInfo = new HashMap<>();
                conceptInfo.put(APIKeys.KEY_CONCEPT_ID, concept.getTerm().toString());
                if(concept.getTerm() instanceof Statement){
                    conceptInfo.put(APIKeys.KEY_BELIEFS,concept.getBeliefs());
                }
                POST(conceptInfo,APIKeys.PATH_SHOW_CONCEPT_INFO);
            }
        }
    }

    /**
     * * ===============================
     * POST REQUESTS TO GUI
     * * ===============================
     */

    public void AddNewConcept(Concept concept){
        HashMap<String, Object> data = new HashMap<>();
        data.put(APIKeys.KEY_CONCEPT_ID, concept.getTerm().toString());
        String type = APIKeys.KEY_TERM_TYPE_ATOMIC;
        if(concept.getTerm() instanceof Statement){
            type = APIKeys.KEY_TERM_TYPE_STATEMENT;
        }
        data.put(APIKeys.KEY_TERM_TYPE, type);
        POST(data,APIKeys.PATH_ADD_CONCEPT);
    }

    public void AddTermLink(Concept source, TermLink termLink){
        HashMap<String, Object> data = new HashMap<>();
        data.put(APIKeys.KEY_LINK_SOURCE, source.getTerm().toString());
        data.put(APIKeys.KEY_LINK_TARGET, termLink.getTarget().toString());
        data.put(APIKeys.KEY_LINK_TYPE, APIKeys.KEY_LINK_TYPE_TERMLINK);
        POST(data,APIKeys.PATH_ADD_LINK);
    }

    public void AddTaskToBuffer(Task task, Buffer buffer){
        HashMap<String, Object> data = new HashMap<>();
        data.put(APIKeys.KEY_BUFFER_NAME, buffer.getName());
        data.put(APIKeys.KEY_SENTENCE, task.getSentence().toStringBrief());
        data.put(APIKeys.KEY_BUDGET, task.getBudget().toStringBrief());
        data.put(APIKeys.KEY_SENTENCE_ID, String.valueOf(task.getSentence().getStamp().evidentialBase[0]));
        POST(data,APIKeys.PATH_ADD_TASK_TO_BUFFER);
    }

    public void RemoveTaskFromBuffer(Task task, Buffer buffer){
        HashMap<String, Object> data = new HashMap<>();
        data.put(APIKeys.KEY_BUFFER_NAME, buffer.getName());
        data.put(APIKeys.KEY_SENTENCE, task.getSentence().toStringBrief());
        data.put(APIKeys.KEY_BUDGET, task.getBudget().toStringBrief());
        data.put(APIKeys.KEY_SENTENCE_ID, String.valueOf(task.getSentence().getStamp().evidentialBase[0]));
        POST(data,APIKeys.PATH_REMOVE_TASK_FROM_BUFFER);
    }

    public void POST(HashMap<String, Object> jsonMap, String path){
        //post
        URL url = null;
        try {
            url = new URL(baseURLstring + path);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST"); // PUT is another valid option
            con.setDoOutput(true);

            // Send INITIALIZE POST request
            Gson gson = new Gson();
            String jsonString = gson.toJson(jsonMap);

            byte[] out = jsonString.getBytes(StandardCharsets.UTF_8);
            System.out.println(jsonString);
            int length = out.length;

            con.setFixedLengthStreamingMode(length);
            con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            con.connect();
            try (OutputStream os = con.getOutputStream()) {
                os.write(out);
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), "utf-8"))) {
                StringBuilder response = new StringBuilder();
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                System.out.println(response.toString());
            }
        }catch (IOException e){
            e.printStackTrace();
        }

    }

}