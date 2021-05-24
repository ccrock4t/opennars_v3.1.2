/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package nars.storage;

import java.util.ArrayList;
import nars.Processing.Anticipation;
import nars.entity.BudgetValue;
import nars.entity.Concept;
import nars.entity.Sentence;
import nars.entity.Stamp;
import nars.entity.Task;
import nars.entity.TruthValue;
import nars.inference.LocalRules;
import nars.inference.SyllogisticRules;
import nars.inference.TemporalRules;
import nars.inference.UtilityFunctions;
import nars.io.Symbols;
import nars.language.Equivalence;
import nars.language.Conjunction;
import nars.language.Implication;
import nars.language.Negation;
import nars.language.Term;
import nars.main_nogui.Parameters;

/**
 *
 * @author Xiang
 */
public abstract class EventBuffer extends Buffer<Task> {
    
    // sequenceList用于存放事件
    private final ArrayList<Task> sequenceList;
    // private final ArrayList<Task> 
    // duration，一个任务在buffer中存在的时间，超过就删除
    private final int duration;
    // 存放一定量的预期列表，用来观察预期时间是否满足
    private final ArrayList<Anticipation> expectationList;
    
    public EventBuffer(Memory memory, int duration, String name) {
        super(memory, name);
        this.duration = duration;
        sequenceList = new ArrayList();
        expectationList = new ArrayList();
    }
    
    @Override
    // capacity of sequenceList
    protected abstract int capacity();
    /**
     * capacity of the expectationList, means how many expectation 
     * the system can expect at most
     * expectationList容量，表示在同一时间，系统可以期待多少事情
     * 列表由前置任务的优先级排序
     * @return 
    */
    protected abstract int anticipationCapacity();
    
    /**
     * Process Anticipation, go over the current expectation list, if the expectation of the task is higher than
     * the expectation of the expected task, also, the occurrence time less than expected occurrence time,
     * add to the fulfilledAnticipation list
     * @param task 
     */
    public void processAnticipation(Task task){
        
        // if the task is not input, which means from the inference, return
        if(!task.isInput())
            return;
        
        ArrayList<Anticipation> fulfilledAnticipation = new ArrayList();
        // No additional implementation is required for the positive evidence because if the expected task happens
        // the implication relation will be generated by the temporal induction any way
        
        for (Anticipation expectation : expectationList) {
            
            if(task.getName().equals(expectation.getTask().getName()) 
                    && task.getStamp().getOccurrenceTime() > expectation.getPreconditionOccurrenceTime()
                    && task.getStamp().getOccurrenceTime() <= expectation.getExpectationOccurrenceTime()){        
                fulfilledAnticipation.add(expectation);
            }
        }
        
        // remove all the fulfilled anticipation
        if(!fulfilledAnticipation.isEmpty())
            expectationList.removeAll(fulfilledAnticipation);
        
    }
    
    /**
     * Preprocessing the input, temporal induction happens here, instead of observation
     * @param task
     * @param allowOverlap 
     */
    public void preProcessing(Task task, boolean allowOverlap){
        
        ArrayList<Task> insert;
        
        // temporal induction only be implemented for input or the temporal induction
        // result, for non-input input, only insert into lists
        if(!task.isInput()){
            putInSequenceList(task, this.getMemory().getTime());
        }else{
            // insert the item to both the priority list and sequence list first
            putInSequenceList(task, this.getMemory().getTime());          
            // implement the temporal induction and return sequence result
            // (&/, a, b) to a list
            insert = eventInference(task, allowOverlap);           
            // insert the sequence conjunction to lists
            if(insert != null){
                for (Task t : insert) {
                    putInSequenceList(t, this.getMemory().getTime());
                }
            }
        }
        
    }
    
    /**
     * Event inference, when a new event come into the buffer, first see if
     * it fulfill the standard for temporal induction, if so, do the temporal induction
     * @param newEvent 
     * @param allowOverlap if overlap of evidence is allow in this buffer
     * @return 
     */
    protected ArrayList<Task> eventInference(Task newEvent, boolean allowOverlap) {      
        
        // if the new event is null, or the budget is null or new event is not judgment
        // or not input, no temporal induction happens
        if(newEvent.getContent() == null || newEvent.getBudget() == null 
                || !newEvent.getSentence().isJudgment() || !newEvent.isInput())
            return null;
        
        ArrayList<Task> list = new ArrayList(); 
        // if there is only onw item in the sequnce list, no temporal induction happens
        if(sequenceList.size() <= 1){                     
            return null;
        }
        //find out the index of the new event in the sequence list
        int index = sequenceList.indexOf(newEvent);
        // since the new event always added to the tail of the list, so
        // if there is no one before it which means no temporal induction happens
        if(index < 1)
            return null;
        // some of the sequence conjunction has the same occurence time with the previous event
        // for example, if a,b in the list, (&/, a, b) is generated, and in the list, it looks like
        // a, b, (&/, a, b) so when c come into the list, c should do the temporal induction with 
        // b and (&/, a, b), and b and (&/, a,b) share the same occurrence time
        long lastEventOccurrenceTime = sequenceList.get(index-1).getStamp().getOccurrenceTime();
                
        // Go through the entire list to find out all the event that could do the tempora inference with
        // the task
        for(Task t : sequenceList){
            
            // just a double check
            if(t.getContent() == null || t.getBudget() == null 
                || !t.getSentence().isJudgment() || t.isEternal() || !t.isInput())
                continue;
            // if evidence overlap is not allowed but overlao is found, skip the current event
            if(!allowOverlap && Stamp.baseOverlap(newEvent.getStamp(), t.getStamp())){
                continue;
            }
            // if the difference of occurrence time is great than the duration of the buffer, skip
            // should not even happen i think
            if(Math.abs(t.getStamp().getOccurrenceTime() - newEvent.getStamp().getOccurrenceTime()) > duration)
                continue;
            
            this.getMemory().currentTask = newEvent;
            this.getMemory().currentBelief = t.getSentence();
            
            // check if the event has the same occurrence time with the one happens before the task
            boolean sequenceInduction = false;
            
            if(t.getStamp().getOccurrenceTime() == lastEventOccurrenceTime)
                sequenceInduction = true;
            
            Task newTask = null;
            // do the temporal induction
            if(newEvent.getStamp().getOccurrenceTime() < t.getStamp().getOccurrenceTime())
                newTask = TemporalRules.temporalInduction(newEvent.getSentence(), t.getSentence(), newEvent, t.getSentence(), sequenceInduction, this.getMemory());
            else
                newTask = TemporalRules.temporalInduction(t.getSentence(), newEvent.getSentence(), newEvent, t.getSentence(), sequenceInduction, this.getMemory());
            
            if(newTask != null) 
                list.add(newTask);
            
        }
        return list;
    }
    
    /**
     * Clear the expired anticipation
     */
    public void clearExpiredAnticipation(){
        
        ArrayList<Anticipation> failedAnticipation = new ArrayList();
        
        //Go through the entire expectation list, if the expectation is expired, which the current time
        // is greater than the expected happening time, add to the failed list
        for (int i = 0; i < expectationList.size(); i++) {
            
            if(expectationList.get(i).getExpectationOccurrenceTime() < this.getMemory().getTime()){
                failedAnticipation.add(expectationList.get(i));
            }
            
        }
        

        // Since the expected event didn't happen, generate a negative evidence to the implication
        // the negative evidence is for the implication which generated the expectation, witht the same 
        // time interval. It doesn't matter if the expected event happens after the expected time, since another positive
        // evidence will be generated with the new time interval by the temporal induction
        TruthValue negaTruth = new TruthValue(0.0f, Parameters.DEFAULT_JUDGMENT_CONFIDENCE);
        //float quality = (negaTruth == null) ? 1 : BudgetFunctions.truthToQuality(negaTruth);
        //BudgetValue budget = new BudgetValue(Parameters.DEFAULT_EVENT_PRIORITY, Parameters.DEFAULT_EVENT_DURABILITY, quality);
        
        // go over the failed anticipatio nlist and generate negative evidence and do the revision directly
        for (int i = 0; i < failedAnticipation.size(); i++) {
            
            Stamp stamp = new Stamp(this.getMemory().getTime());
            stamp.setEternal();
            Sentence sentence = new Sentence(failedAnticipation.get(i).getRelation(), Symbols.JUDGMENT_MARK, negaTruth, stamp);
            Concept c = this.getMemory().getConcept(sentence.getContent());
            
            Sentence oldBelief = null;
            
            if(!c.getBeliefs().isEmpty())
                oldBelief = c.evaluation(sentence, c.getBeliefs());
            
            //this.getMemory().generalInfoReport("Failed: " + sentence.getContent().getName());
            
            if (oldBelief != null) {
                
                //this.getMemory().generalInfoReport("?????");
                
                Stamp newStamp = sentence.getStamp();
                Stamp oldStamp = oldBelief.getStamp();
                
                if (newStamp.equals(oldStamp)) {  
                } else if (LocalRules.revisible(sentence, oldBelief)) {
                    this.getMemory().newStamp = Stamp.make(newStamp, oldStamp, this.getMemory().getTime());
                    if (this.getMemory().newStamp != null) {
                        this.getMemory().currentBelief = oldBelief;
                        //this.getMemory().generalInfoReport("Revision");
                        LocalRules.revision(sentence, oldBelief, false, this.getMemory());
                    }
                }
            }
            
        }      
        // after generating the negative evidence, remove the expectation
        expectationList.removeAll(failedAnticipation);
        
    }
    
    /**
     * find the insert position in the sequence list, the list sort by the occurrence time
     * @param task
     * @return 
     */
    protected int findIntertIndexInSequenceList(Task task){
        
        int i = sequenceList.size() - 1 ;
        // 遍历整个list找到第一个put in time大于新任务的put in time，返回插入位置
        // go over the entire list from the tail and find the first one which the occurrence less than the occurrence time
        // of the task and return the next position
        while(i >= 0) {
            
            if(task.getStamp().getOccurrenceTime() >= sequenceList.get(i).getStamp().getOccurrenceTime())
                return i + 1;
            
            i--;
            
        }
        
        return i;
        
    }
    
    /**
     * Insert an task into the sequence list
     * @param task 将要被插入的任务
     * @param time 时间
     * @return 是否成功插入
     */
    public boolean putInSequenceList(Task task, long time){
        
        // 将任务的放入时间设置为给定的时间
        task.getStamp().setPutInTime(time);    

        boolean insert;
        
        // for eternal task or goal, only insert into the priority list
        if(task.isEternal() || task.getSentence().isGoal() || task.getSentence().isQuestion()){
            
            if(putIn(task, this.getItemTable(), capacity()))
                return true;
            else
                return false;
            
        }           

        // if successfully insert into the priority list, insert the event to the sequence list
        if(putIn(task, this.getItemTable(), capacity()) && !task.isEternal() && !task.getSentence().isGoal() && task.isInput()){
            
            // No duplicated event is allowed
            // same task with same occurrence time
            if(checkDuplicates(task, sequenceList))
                return false;                     
            
            // if the sequencelist is empty, directly insert into the list
            if(sequenceList.isEmpty()){
                sequenceList.add(task);
                insert = true;
            }else{
                
                // if the size of the list less than the capacity, find the position
                // and insert into it
                if(sequenceList.size() < capacity()){                   
                    
                    if(findIntertIndexInSequenceList(task) == -1)
                        sequenceList.add(0, task);
                    else
                        sequenceList.add(findIntertIndexInSequenceList(task), task);
                    
                    insert = true;
                }else{
                    
                    // if the sequnce list is full, check if the oldest one can be remove and insert the new one into it
                    if(task.getStamp().getOccurrenceTime() > sequenceList.get(0).getStamp().getOccurrenceTime()){
                        sequenceList.remove(0);
                    if(findIntertIndexInSequenceList(task) == -1)
                        sequenceList.add(0, task);
                    else
                        sequenceList.add(findIntertIndexInSequenceList(task), task);
                             
                        insert = true;
                    }else{
                        // if the occurence time of the new task less than every task in the list
                        // insert failed
                        insert = false;
                    }
                }
            }

            if(insert){            

                // if the task is insert into the list process anticipation
                // clear expired anticipation and generate new expectation
                processAnticipation(task);
                clearExpiredAnticipation();
                generateExpectation(task);           
                
                return true;
            }else{
                return false;
            }
            
        }
        return false;
    }
    
    /**
     * generate the expectation
     * @param task 
     */
    public void generateExpectation(Task task){
             
        // if the task is already generate the anticipation or the task is not a input
        // return directly
        if(task.getGeneratedAnticipation() || !task.isInput())
            return; 
               
        // 如果任务发生时间与当前时间的时间差大于Buffer本身可容纳的时间间隔，则直接返回
        // if the difference between the ot(occurrence time) and the current time is greater than 
        // the duration, return
        if(Math.abs(task.getStamp().getOccurrenceTime() - this.getMemory().getTime()) > Parameters.DURATION_FOR_GLOBAL_BUFFER)
            return;

        // check if there is a concept to the task exist
        // No new concept is generated here, since no concept, no corresponding 
        // implication for the task
        Concept concept = this.getMemory().getConceptFromTheList(task.getContent());   
        
        // if the concept is not null and the anticipation list is not null,
        // and the task is an event, generate expectation
        if(concept != null && !concept.getAnticipationList().isEmpty() && !task.isEternal()){    
            
            // go over the entire anticipation list
            for(Concept anticipation : concept.getAnticipationList()){               
                // use the first rank in the belief table of the concept to generate
                // the expectation
                Sentence belief = null;
                if(!anticipation.getBeliefs().isEmpty()){
                    belief = anticipation.getBeliefs().get(0);
                }

                if(belief == null)
                    return;
                // get ready for the detachment
                this.getMemory().currentBelief = belief;
                this.getMemory().currentTask = task;                            
                
                if(belief.getContent() instanceof Implication){        
                    
                    if(belief.getTemporalOrder() == TemporalRules.ORDER_FORWARD){
                        // Expectation is generated by the detachment with an truth value
                        Task expectEvent = SyllogisticRules.detachment(belief, task.getSentence(), 0, this.getMemory(), true);
                                          
                        if(expectEvent == null)
                            return;                      
                        
                        Anticipation expectation = new Anticipation(expectEvent, belief.getContent(), expectEvent.getStamp().getOccurrenceTime(), task.getStamp().getOccurrenceTime());
                        // Emotion realted to the anticipation happens here
                        // for people who doesn't care about the emotion, skip this part
                        if(addToExpectationList(expectation)){
                            
                            /*if(((Implication)belief.getContent()).getSubject() instanceof Conjunction){
                                //this.getMemory().generalInfoReport("Anticipate " + expectation.getTask().getName() + " to happen at " + expectEvent.getStamp().getOccurrenceTime());
                                this.getMemory().generalInfoReport("Anticipate " + expectation.getTask().getName() 
                                                               + expectation.getTask().getSentence().getPunctuation() + " with expectation " 
                                                               + expectation.getTask().getSentence().getTruth().getExpectation()
                                                               +" to happen at " + expectEvent.getStamp().getOccurrenceTime());
                                this.getMemory().generalInfoReport("Relation: " + belief.getContent().getName() + " " + belief.getTruth().getExpectation());
                            }*/
                            
                            Concept c = this.getMemory().getConcept(expectEvent.getContent());                       
                            
                            if(c != null && !c.getDesires().isEmpty()){
                                
                                if(expectEvent.getSentence().getTruth().getExpectation() < 0.45){
                            
                                    if(c.getDesires().get(0).getSentence().getTruth().getExpectation() > 0.5){                                       
                                        
                                        this.getMemory().generalInfoReport("NARS is feeling fear. Because NARS desires " 
                                                                            + expectEvent.getName() + "\nto happen, with expectation " 
                                                                            + c.getDesires().get(0).getSentence().getTruth().getExpectation() 
                                                                            + ",\n but NARS anticipates " + expectEvent.getName() + "will not happen, with expectation "
                                                                            + expectEvent.getSentence().getTruth().getExpectation());                                                                             
                                        
                                        float weight = expectEvent.getPriority();
                                        float fear = Math.abs(expectEvent.getSentence().getTruth().getExpectation() - c.getDesires().get(0).getSentence().getTruth().getExpectation());
                                        float timeDiff = Math.abs(expectEvent.getStamp().getOccurrenceTime() - this.getMemory().getTime());
                                        float timeImpact = 1 - timeDiff/11;                                    
                                        
                                        
                                        
                                        c.getDesires().get(0).setBestSolution(expectEvent.getSentence());                                           
                                        
                                        float newPriority = UtilityFunctions.or(c.getPriority(), weight, fear, timeImpact);
                                        float newDurability = UtilityFunctions.or(c.getDurability(), weight, fear, timeImpact);
                                        
                                        BudgetValue buget = new BudgetValue(newPriority, newDurability, c.getQuality());
                                        
                                        this.getMemory().activateConcept(c, buget);   
                                    }
                            
                                }else if(expectEvent.getSentence().getTruth().getExpectation() > 0.55){                                  
                                    
                                    if(c.getDesires().get(0).getSentence().getTruth().getExpectation() <= 0.5){                                      
                                        
                                        this.getMemory().generalInfoReport("NARS is feeling fear. Because NARS does not desire " 
                                                                            + expectEvent.getName() + "\n to happen, with expectation " 
                                                                            + c.getDesires().get(0).getSentence().getTruth().getExpectation() 
                                                                            + ",\n but NARS anticipates " + expectEvent.getName() + " will happen, with expectation "
                                                                            + expectEvent.getSentence().getTruth().getExpectation());                                 
                                        
                                        
                                        
                                        float weight = expectEvent.getPriority();
                                        float fear = Math.abs(expectEvent.getSentence().getTruth().getExpectation() - c.getDesires().get(0).getSentence().getTruth().getExpectation());
                                        float timeDiff = Math.abs(expectEvent.getStamp().getOccurrenceTime() - this.getMemory().getTime());
                                        float timeImpact = 1 - timeDiff/11;
                                        
                                        Term negation = Negation.make(expectEvent.getContent(), this.getMemory());
                                        
                                        Concept c1 = this.getMemory().getConceptFromTheList(negation);                                      
                                        
                                        if(c1 == null)
                                            return;
                                        
                                        c1.getDesires().get(0).setBestSolution(expectEvent.getSentence());                                              
                                        
                                        float newPriority = UtilityFunctions.or(c1.getPriority(), weight, fear, timeImpact);
                                        float newDurability = UtilityFunctions.or(c1.getDurability(), weight, fear, timeImpact);
                                        
                                        BudgetValue buget = new BudgetValue(newPriority, newDurability, c1.getQuality());
                                        
                                        //this.getMemory().activateConcept(c1, buget);                                   
                                        
                                    }
                                    
                                }
                                
                            }

                        }
                    // same thing for the back ward implication, not complete
                    }else if(belief.getTemporalOrder() == TemporalRules.ORDER_BACKWARD){
                        
                        Task expectEvent = SyllogisticRules.detachment(belief, task.getSentence(), 1, this.getMemory(), true);  
                        
                        if(expectEvent.getSentence().getTruth().getExpectation() < 0.5)
                            return;
                        
                        Anticipation expectation = new Anticipation(expectEvent, belief.getContent(), expectEvent.getStamp().getOccurrenceTime(), task.getStamp().getOccurrenceTime());                                                                      
                        if(addToExpectationList(expectation)){
                            //System.out.println("Anticipate " + expectation.getTask().getName() + " to happen at " + expectEvent.getStamp().getOccurrenceTime());
                            /*this.getMemory().generalInfoReport("Anticipate " + expectation.getTask().getName() 
                                                               + expectation.getTask().getSentence().getPunctuation() + " " 
                                                               + expectation.getTask().getSentence().getTruth() 
                                                               +" to happen at " + expectEvent.getStamp().getOccurrenceTime());*/
                        }
                        
                    }
                    //for equivalence
                }else if(belief.getContent() instanceof Equivalence){
                    if(belief.getTemporalOrder() == TemporalRules.ORDER_FORWARD){
                        Task expectEvent = SyllogisticRules.detachment(belief, task.getSentence(), 0, this.getMemory(), true);
                        if(expectEvent.getSentence().getTruth().getExpectation() < 0.5)
                            return;
                        Anticipation expectation = new Anticipation(expectEvent, belief.getContent(), expectEvent.getStamp().getOccurrenceTime(), task.getStamp().getOccurrenceTime());  
                        if(addToExpectationList(expectation)){
                            //System.out.println("Anticipate " + expectation.getTask().getName() + " to happen at " + expectEvent.getStamp().getOccurrenceTime());
                            /*this.getMemory().generalInfoReport("Anticipate " + expectation.getTask().getName() 
                                                               + expectation.getTask().getSentence().getPunctuation() + " " 
                                                               + expectation.getTask().getSentence().getTruth() 
                                                               +" to happen at " + expectEvent.getStamp().getOccurrenceTime());*/
                        }
                    }     
                }
            }
            task.setGeneratedAnticipation(true);
        }        
    }
    
    /**
     * Add to the expectation list
     * @param expectation
     * @return 
     */
    public boolean addToExpectationList(Anticipation expectation){
        
        // if the list is empty directly add into it
        if(expectationList.isEmpty()){
            expectationList.add(expectation);
            return true;
        }else{
            // check duplicates
            for (Anticipation anticipation : expectationList) {
                
                if(expectation.getTask().getName().equals(anticipation.getTask().getName()) 
                        && expectation.getExpectationOccurrenceTime() == anticipation.getExpectationOccurrenceTime()
                        && expectation.getRelation().getName().equals(anticipation.getRelation().getName()))
                    return false;
                
            }
            // add based on the priority, if priority is too low, dont care about it
            if(expectationList.size() < anticipationCapacity()){
                if(expectation.getTask().getPriority() < expectationList.get(expectationList.size() - 1).getTask().getPriority()){
                    expectationList.add(expectation);
                    return true;
                }else{
                    for (int i = 0; i < expectationList.size(); i++) {
                    
                        if(expectation.getTask().getPriority() > expectationList.get(i).getTask().getPriority()){
                            expectationList.add(i, expectation);
                            return true;
                        }
                    }
                    expectationList.add(expectation);
                    return true;
                }
                
            }else{
                if(expectation.getTask().getPriority() < expectationList.get(expectationList.size() - 1).getTask().getPriority()){
                    return false;
                }else{
                    
                    expectationList.remove(expectationList.get(expectationList.size() - 1));
                    
                    for (int i = 0; i < expectationList.size(); i++) {
                    
                        if(expectation.getTask().getPriority() > expectationList.get(i).getTask().getPriority()){
                            expectationList.add(i, expectation);
                            return true;
                        }
                    
                    }
                    expectationList.add(expectation);
                    return true;
                }   
            }
        }
    }    

    /**
     * check duplicates in the list
     * @param task
     * @param list
     * @return 
     */
    public boolean checkDuplicates(Task task, ArrayList<Task> list){
        
        for (Task t : list) {
            
            if(task.getName().equals(t.getName()) && task.getStamp().getOccurrenceTime() == t.getStamp().getOccurrenceTime())
                return true;
            
        }
          
        return false;
    }
    
    /**
     * Similar to the insert into sequence list, but sort by priority
     * @param task
     * @param list
     * @param capacity
     * @return 
     */
    @Override
    public boolean putIn(Task task, ArrayList<Task> list, int capacity) {
        
        if(checkDuplicates(task,list))
            return false;
        
        if(list.isEmpty()){
            list.add(task);
            return true;
            
        }else{          
            
            if(list.size() < capacity){
                
                if(findInsertIndex(task) < list.size())
                    list.add(findInsertIndex(task), task);
                else
                    list.add(task);
                
                return true;
            }else{
                
                if(task.getPriority() > list.get(capacity - 1).getPriority()){
                    list.remove(list.size() - 1);
                    list.add(findInsertIndex(task), task);
                    return true;
                }else{
                    return false;
                }
            }
        }
        
    }

    /**
     * Finding the insert index for inserting into the priority list
     * @param task
     * @return 
     */
    @Override
    protected int findInsertIndex(Task task) {
        
        int i = 0;
        for (; i < this.getItemTable().size(); i++) {
            
            if(task.getPriority() > this.getItemTable().get(i).getPriority())
                return i;

        }
        return i;
        
    }
    
    public ArrayList<Task> getSequenceList(){
        return sequenceList;
    }
    
    /**
     * Clear the expired items, if the difference of the put in time and the current time
     * greater than the duration which mean stay in the buffer for too long, remove it
     */
    public void clearExpiredItem(){
        
        ArrayList<Task> expiredKey = new ArrayList();
        
        (this.getItemTable()).forEach((task) -> {
            if((this.getMemory().getTime() - task.getSentence().getStamp().getPutInTime()) > duration){
                expiredKey.add(task);
            }
        });
        
        for (int i = 0; i < sequenceList.size(); i++) {
            
            if(this.getMemory().getTime() - sequenceList.get(i).getStamp().getOccurrenceTime() > duration){
                
                if(!expiredKey.contains(sequenceList.get(i)))
                    expiredKey.add(sequenceList.get(i));
                
            }
            
        }
        
        this.getItemTable().removeAll(expiredKey);
        sequenceList.removeAll(expiredKey);
        
    }

    /**
     * Take out
     * @return 
     */
    @Override
    protected Task takeOut() {
        // clear the expired items first
        clearExpiredItem();
        
        // if the item table is not null, return the first one which the one with highest priority      
        if(!this.getItemTable().isEmpty()){
            Task task = this.getItemTable().get(0);
            this.getItemTable().remove(0);
            return task;
            // if the list is empty return null
        }else{
            return null;
        }
        
    }

    @Override
    protected boolean isEmpty() {
        return this.getItemTable().isEmpty();
    }
    
    /**
     * Only does the take out, the temporal induction move to the insertion
     * @param temporalInduction
     * @param allowOverlap
     * @return 
     */
    public Task observe(boolean temporalInduction, boolean allowOverlap){
        Task task = takeOut();
        return task;
    }
    
    public ArrayList<Anticipation> getExpectationList(){
        return expectationList;
    }
}
