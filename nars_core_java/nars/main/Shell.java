/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package nars.main;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import nars.io.OutputChannel;

/**
 * @author Pei, Xiang, Patrick, Peter
 */
public class Shell {
    
    static String inputString = "";
    static boolean paused = true;

    private static class InputThread extends Thread {
        private final BufferedReader bufIn;
        private final NAR reasoner;

        InputThread(final InputStream in, NAR reasoner) {
            this.bufIn = new BufferedReader(new InputStreamReader(in));
            this.reasoner = reasoner;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    final String line = bufIn.readLine();
                    if (line != null) {
                        synchronized(inputString) {
                            inputString = line;
                            if("".equals(line)) {
                                inputString = "1";
                            }
                        }
                    }

                } catch (final IOException e) {
                    throw new IllegalStateException("Could not read line.", e);
                }

                try {
                    Thread.sleep(1);
                } catch (final InterruptedException e) {
                    throw new IllegalStateException("Unexpectadly interrupted while sleeping.", e);
                }
            }
        }
    }
    
    
    public static class ShellOutput implements OutputChannel{
        @Override
        public void nextOutput(ArrayList<String> arg0) {
            for(String s : arg0){
                if(!s.matches("[0-9]+")) {System.out.println(s);}
            }
        }
        @Override
        public void tickTimer() {}
    }

    
    public static void main(String[] args) throws InterruptedException {
        NAR reasoner = new NAR();
        reasoner.addOutputChannel(new ShellOutput());
        InputThread t = new InputThread(System.in, reasoner);
        t.start();
        System.out.println("Welcome to OpenNARS v" + Parameters.VERSION  + 
                           " Shell! Type Narsese input and press enter, use questions to get "
                         + "answers or increase volume with *volume=n with n=0..100");
        reasoner.run();
        reasoner.getSilenceValue().set(0);

        while(true){
            Thread.sleep(1);
            if(reasoner.useGUI){
                reasoner.GUI.ProcessSocket();
            }
            synchronized(inputString) {
                if(!"".equals(inputString)) {
                    try {
                        if(inputString.startsWith("*volume=")) { //volume to be consistent with OpenNARS
                            int val = Integer.parseInt(inputString.split("\\*volume=")[1]);
                            if (val >= 0 && val <= 100) {
                                reasoner.getSilenceValue().set(100 - val);
                            } else {
                                System.out.println("Volume ignored, not in range");
                            }
                        }else if(inputString.startsWith("*cycle")){
                            System.out.println("Cycle #" + reasoner.getTimer());
                        }else if(inputString.startsWith("*paused")){
                            paused = !paused;
                            System.out.println("Setting paused to: " + paused);
                        } else {
                            reasoner.textInputLine(inputString);
                        }
                        inputString = "";
                    } catch(Exception ex) {
                        inputString = "";
                    }
                }
            }
            if(reasoner.getWalkingSteps() > 0 || (!paused))
                reasoner.cycle();
        }
    }
}
