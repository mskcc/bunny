package org.rabix.engine.processor.handler.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.bindings.model.dag.DAGLinkPort.LinkPortType;
import org.rabix.common.helper.CloneHelper;
import org.rabix.common.helper.InternalSchemaHelper;
import org.rabix.engine.JobHelper;
import org.rabix.engine.db.DAGNodeDB;
import org.rabix.engine.event.Event;
import org.rabix.engine.event.impl.InputUpdateEvent;
import org.rabix.engine.event.impl.JobStatusEvent;
import org.rabix.engine.event.impl.OutputUpdateEvent;
import org.rabix.engine.model.JobRecord;
import org.rabix.engine.model.LinkRecord;
import org.rabix.engine.model.VariableRecord;
import org.rabix.engine.model.scatter.ScatterStrategy;
import org.rabix.engine.processor.EventProcessor;
import org.rabix.engine.processor.handler.EventHandler;
import org.rabix.engine.processor.handler.EventHandlerException;
import org.rabix.engine.service.CacheService;
import org.rabix.engine.service.RootJobService;
import org.rabix.engine.service.JobRecordService;
import org.rabix.engine.service.LinkRecordService;
import org.rabix.engine.service.VariableRecordService;
import org.rabix.engine.status.EngineStatusCallback;
import org.rabix.engine.status.EngineStatusCallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Handles {@link OutputUpdateEvent} events.
 */
public class OutputEventHandler implements EventHandler<OutputUpdateEvent> {

  private final static Logger logger = LoggerFactory.getLogger(OutputEventHandler.class);
  
  private JobRecordService jobService;
  private LinkRecordService linkService;
  private VariableRecordService variableService;
  private RootJobService contextService;
    
  private final EventProcessor eventProcessor;
  
  private DAGNodeDB dagNodeDB;
  private EngineStatusCallback engineStatusCallback;
  
  @Inject
  public OutputEventHandler(EventProcessor eventProcessor, JobRecordService jobService, VariableRecordService variableService, LinkRecordService linkService, RootJobService contextService, DAGNodeDB dagNodeDB) {
    this.dagNodeDB = dagNodeDB;
    
    this.jobService = jobService;
    this.linkService = linkService;
    this.contextService = contextService;
    this.variableService = variableService;
    this.eventProcessor = eventProcessor;
  }

  public void initialize(EngineStatusCallback engineStatusCallback) {
    this.engineStatusCallback = engineStatusCallback;
  }
  
  public void handle(final OutputUpdateEvent event) throws EventHandlerException {
    JobRecord sourceJob = jobService.find(event.getJobName(), event.getRootId());
    if (event.isFromScatter()) {
      jobService.resetOutputPortCounter(sourceJob, event.getNumberOfScattered(), event.getPortId());
    }
    VariableRecord sourceVariable = variableService.find(event.getJobName(), event.getPortId(), LinkPortType.OUTPUT, event.getRootId());
    jobService.decrementPortCounter(sourceJob, event.getPortId(), LinkPortType.OUTPUT);
    variableService.addValue(sourceVariable, event.getValue(), event.getPosition(), sourceJob.isScatterWrapper());
    variableService.update(sourceVariable); // TODO wha?
    jobService.update(sourceJob);
    
    if (sourceJob.isCompleted()) {
      sourceJob.setState(JobRecord.JobState.COMPLETED);
      jobService.update(sourceJob);
      
      try {
        Job completedJob = JobHelper.createCompletedJob(sourceJob, JobStatus.COMPLETED, jobService, variableService, linkService, contextService, dagNodeDB);
        engineStatusCallback.onJobCompleted(completedJob);
      } catch (BindingException e) {
        logger.error("Failed to create Job " + sourceJob.getName(), e);
      } catch (EngineStatusCallbackException e) {
        logger.error("Failed to call onJobCompleted callback for Job " + sourceJob.getName(), e);
      }
      
      if (sourceJob.isRoot()) {
        Map<String, Object> outputs = new HashMap<>();
        List<VariableRecord> outputVariables = variableService.find(sourceJob.getName(), LinkPortType.OUTPUT, sourceJob.getRootId());
        for (VariableRecord outputVariable : outputVariables) {
          Object value = CloneHelper.deepCopy(variableService.getValue(outputVariable));
          outputs.put(outputVariable.getPortId(), value);
        }
        if(sourceJob.isRoot() && sourceJob.isContainer()) {
          // if root job is CommandLineTool OutputUpdateEvents are created from JobStatusEvent
          eventProcessor.send(new JobStatusEvent(sourceJob.getName(), JobRecord.JobState.COMPLETED, event.getRootId(), outputs, event.getEventGroupId()));
        }
        return;
      }
    }
    
    if (sourceJob.isRoot()) {
      try {
        engineStatusCallback.onJobRootPartiallyCompleted(createRootJob(sourceJob, JobHelper.transformStatus(sourceJob.getState())));
      } catch (EngineStatusCallbackException e) {
        logger.error("Failed to call onReady callback for Job " + sourceJob.getName(), e);
        throw new EventHandlerException("Failed to call onJobRootPartiallyCompleted callback for Job " + sourceJob.getName(), e);
      }
    }
    
    Object value = null;
    
    if (sourceJob.isScatterWrapper()) {
      ScatterStrategy scatterStrategy = sourceJob.getScatterStrategy();
      
      boolean isValueFromScatterStrategy = false;
      if (scatterStrategy.isBlocking()) {
        if (sourceJob.isOutputPortReady(event.getPortId())) {
          isValueFromScatterStrategy = true;
          value = scatterStrategy.values(variableService, sourceJob.getName(), event.getPortId(), event.getRootId());
        } else {
          return;
        }
      }
      
      List<LinkRecord> links = linkService.findBySource(sourceVariable.getJobName(), sourceVariable.getPortId(), event.getRootId());
      for (LinkRecord link : links) {
        if (!isValueFromScatterStrategy) {
          value = null; // reset
        }
        List<VariableRecord> destinationVariables = variableService.find(link.getDestinationJobName(), link.getDestinationJobPort(), event.getRootId());

        JobRecord destinationJob = null;
        boolean isDestinationPortScatterable = false;
        for (VariableRecord destinationVariable : destinationVariables) {
          switch (destinationVariable.getType()) {
          case INPUT:
            destinationJob = jobService.find(destinationVariable.getJobName(), destinationVariable.getRootId());
            isDestinationPortScatterable = destinationJob.isScatterPort(destinationVariable.getPortId());
            if (isDestinationPortScatterable && !destinationJob.isBlocking() && !(destinationJob.getInputPortIncoming(event.getPortId()) > 1)) {
              value = value != null ? value : event.getValue();
              int numberOfScattered = sourceJob.getNumberOfGlobalOutputs();
              Event updateInputEvent = new InputUpdateEvent(destinationVariable.getJobName(), event.getRootId(), destinationVariable.getPortId(), value, event.getPosition(), numberOfScattered, true, event.getEventGroupId());
              eventProcessor.send(updateInputEvent);
            } else {
              if (sourceJob.isOutputPortReady(event.getPortId())) {
                value = value != null ? value : variableService.getValue(sourceVariable);
                Event updateInputEvent = new InputUpdateEvent(event.getRootId(), destinationVariable.getJobName(), destinationVariable.getPortId(), value, link.getPosition(), event.getEventGroupId());
                eventProcessor.send(updateInputEvent);
              }
            }
            break;
          case OUTPUT:
            destinationJob = jobService.find(destinationVariable.getJobName(), destinationVariable.getRootId());
            if (destinationJob.getOutputPortIncoming(event.getPortId()) > 1) {
              if (sourceJob.isOutputPortReady(event.getPortId())) {
                value = value != null? value : variableService.getValue(sourceVariable);
                Event updateInputEvent = new OutputUpdateEvent(event.getRootId(), destinationVariable.getJobName(), destinationVariable.getPortId(), value, link.getPosition(), event.getEventGroupId());
                eventProcessor.send(updateInputEvent);
              }
            } else {
              value = value != null? value : event.getValue();
              if (isValueFromScatterStrategy) {
                Event updateOutputEvent = new OutputUpdateEvent(destinationVariable.getJobName(), event.getRootId(), value, destinationVariable.getPortId(), 1, false, 1, event.getEventGroupId());
                eventProcessor.send(updateOutputEvent);
              } else {
                int numberOfScattered = sourceJob.getNumberOfGlobalOutputs();
                Event updateOutputEvent = new OutputUpdateEvent(destinationVariable.getJobName(), event.getRootId(), value, destinationVariable.getPortId(), numberOfScattered, true, event.getPosition(), event.getEventGroupId());
                eventProcessor.send(updateOutputEvent);
              }
            }
            break;
          }
        }
      }
      return;
    }
    
    if (sourceJob.isOutputPortReady(event.getPortId())) {
      List<LinkRecord> links = linkService.findBySource(event.getJobName(), event.getPortId(), event.getRootId());
      for (LinkRecord link : links) {
        List<VariableRecord> destinationVariables = variableService.find(link.getDestinationJobName(), link.getDestinationJobPort(), event.getRootId());
        
        value = variableService.getValue(sourceVariable);
        for (VariableRecord destinationVariable : destinationVariables) {
          switch (destinationVariable.getType()) {
          case INPUT:
            Event updateInputEvent = new InputUpdateEvent(event.getRootId(), destinationVariable.getJobName(), destinationVariable.getPortId(), value, link.getPosition(), event.getEventGroupId());
            eventProcessor.send(updateInputEvent);
            break;
          case OUTPUT:
            if (sourceJob.isScattered()) {
              int numberOfScattered = sourceJob.getNumberOfGlobalOutputs();
              int position = InternalSchemaHelper.getScatteredNumber(sourceJob.getName());
              Event updateOutputEvent = new OutputUpdateEvent(destinationVariable.getJobName(), event.getRootId(), value, destinationVariable.getPortId(), numberOfScattered, true, position, event.getEventGroupId());
              eventProcessor.send(updateOutputEvent);
            } else {
              Event updateOutputEvent = new OutputUpdateEvent(event.getRootId(), destinationVariable.getJobName(), destinationVariable.getPortId(), value, link.getPosition(), event.getEventGroupId());
              eventProcessor.send(updateOutputEvent);
            }
            break;
          }
        }
      }
    }
  }
  
  private Job createRootJob(JobRecord jobRecord, JobStatus status) {
    Map<String, Object> outputs = new HashMap<>();
    List<VariableRecord> outputVariables = variableService.find(jobRecord.getName(), LinkPortType.OUTPUT, jobRecord.getRootId());
    for (VariableRecord outputVariable : outputVariables) {
      Object value = CloneHelper.deepCopy(variableService.getValue(outputVariable));
      outputs.put(outputVariable.getPortId(), value);
    }
    return JobHelper.createRootJob(jobRecord, status, jobService, variableService, linkService, contextService, dagNodeDB, outputs);
  }
  
}
