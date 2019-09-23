package org.rabix.engine.processor.handler.impl;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.rabix.bindings.model.LinkMerge;
import org.rabix.bindings.model.dag.DAGLinkPort.LinkPortType;
import org.rabix.bindings.model.dag.DAGNode;
import org.rabix.common.helper.InternalSchemaHelper;
import org.rabix.engine.event.Event;
import org.rabix.engine.event.impl.InputUpdateEvent;
import org.rabix.engine.event.impl.JobStatusEvent;
import org.rabix.engine.processor.EventProcessor;
import org.rabix.engine.processor.handler.EventHandler;
import org.rabix.engine.processor.handler.EventHandlerException;
import org.rabix.engine.service.*;
import org.rabix.engine.store.model.JobRecord;
import org.rabix.engine.store.model.LinkRecord;
import org.rabix.engine.store.model.VariableRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@link InputUpdateEvent} events.
 */
public class InputEventHandler implements EventHandler<InputUpdateEvent> {

  @Inject
  private DAGNodeService dagNodeService;
  @Inject
  private JobRecordService jobRecordService;
  @Inject
  private LinkRecordService linkService;
  @Inject
  private VariableRecordService variableService;
  @Inject
  private ScatterHandler scatterHelper;
  @Inject
  private EventProcessor eventProcessor;
  @Inject
  private IntermediaryFilesService intermediaryFilesService;
  private Logger logger = LoggerFactory.getLogger(getClass());
  public final static String TREAT_ROOT = "treatRootInputsAsIntermediary";
  @Inject
  @Named(TREAT_ROOT)
  private Boolean treatRoot;

  @Override
  public void handle(InputUpdateEvent event, EventHandlingMode mode) throws EventHandlerException {
    logger.debug(event.toString());
    JobRecord job = jobRecordService.find(event.getJobId(), event.getContextId());

    if (job == null) {
      logger.info("Possible stale message. Job {} for root {} doesn't exist.", event.getJobId(), event.getContextId());
      return;
    }

    if (job.isRoot() && !treatRoot) {
      intermediaryFilesService.freeze(event.getContextId(), event.getValue());
    } else if (!job.isContainer() && !job.isScatterWrapper()) {
      intermediaryFilesService.incrementInputFilesReferences(event.getContextId(), event.getValue());
    }

    VariableRecord variable = variableService.find(event.getJobId(), event.getPortId(), LinkPortType.INPUT, event.getContextId());
    DAGNode node = dagNodeService.get(InternalSchemaHelper.normalizeId(job.getId()), event.getContextId(), job.getDagHash());

    if (event.isLookAhead()) {
      if (job.isBlocking() || (job.getInputPortIncoming(event.getPortId()) > 1)) {
        return; // guard: should not happen
      } else {
        jobRecordService.resetInputPortCounter(job, event.getNumberOfScattered(), event.getPortId());
      }
    } else if ((job.getInputPortIncoming(event.getPortId()) > 1) && job.isScatterPort(event.getPortId())
        && !LinkMerge.isBlocking(node.getLinkMerge(event.getPortId(), LinkPortType.INPUT))) {
      jobRecordService.resetOutputPortCounters(job, job.getInputPortIncoming(event.getPortId()));
    }

    variableService.addValue(variable, event.getValue(), event.getPosition(), false);
    jobRecordService.decrementPortCounter(job, event.getPortId(), LinkPortType.INPUT);

    // scatter
    if (!job.isBlocking() && !job.isScattered()) {
      if (job.isScatterPort(event.getPortId())) {
        if ((job.isInputPortBlocking(node, event.getPortId()))) {
          // it's blocking
          if (job.isInputPortReady(event.getPortId())) {
            scatterHelper.scatterPort(job, event, event.getPortId(), variableService.getValue(variable), event.getPosition(), event.getNumberOfScattered(),
                event.isLookAhead(), false);
            update(job, variable);
            return;
          }
        } else {
          // it's not blocking
          scatterHelper.scatterPort(job, event, event.getPortId(), event.getValue(), event.getPosition(), event.getNumberOfScattered(), event.isLookAhead(),
              true);
          update(job, variable);
          return;
        }
      } else if (job.isScatterWrapper()) {
        update(job, variable);
        sendValuesToScatteredJobs(job, variable, event);
        return;
      }
    }

    update(job, variable);
    if (job.isReady()) {
      JobStatusEvent jobStatusEvent = new JobStatusEvent(job.getId(), event.getContextId(), JobRecord.JobState.READY, event.getEventGroupId(),
          event.getProducedByNode());
      eventProcessor.send(jobStatusEvent);
    }
  }

  private void update(JobRecord job, VariableRecord variable) {
    jobRecordService.update(job);
    variableService.update(variable);
  }

  /**
   * Send events from scatter wrapper to scattered jobs
   */
  private void sendValuesToScatteredJobs(JobRecord job, VariableRecord variable, InputUpdateEvent event) throws EventHandlerException {
    List<LinkRecord> links = linkService.findBySourceAndDestinationType(job.getId(), event.getPortId(), LinkPortType.INPUT, event.getContextId());

    List<Event> events = new ArrayList<>();
    for (LinkRecord link : links) {
      VariableRecord destinationVariable = variableService.find(link.getDestinationJobId(), link.getDestinationJobPort(), LinkPortType.INPUT,
          event.getContextId());

      Event updateInputEvent = new InputUpdateEvent(event.getContextId(), destinationVariable.getJobId(), destinationVariable.getPortId(),
          variableService.getValue(variable), event.getPosition(), event.getEventGroupId(), event.getProducedByNode());
      events.add(updateInputEvent);
    }
    for (Event subevent : events) {
      eventProcessor.send(subevent);
    }
  }

}
