/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.core.job.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.job.flow.support.StateTransition;
import org.springframework.batch.core.job.flow.support.state.DecisionState;
import org.springframework.batch.core.job.flow.support.state.EndState;
import org.springframework.batch.core.job.flow.support.state.StepState;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.MapJobRepositoryFactoryBean;
import org.springframework.batch.core.step.StepSupport;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

/**
 * @author Dave Syer
 * 
 */
public class FlowJobTests {

	private FlowJob job = new FlowJob();

	private JobExecution jobExecution;

	private JobRepository jobRepository;

	private boolean fail = false;

	@Before
	public void setUp() throws Exception {
		MapJobRepositoryFactoryBean.clear();
		MapJobRepositoryFactoryBean factory = new MapJobRepositoryFactoryBean();
		factory.setTransactionManager(new ResourcelessTransactionManager());
		factory.afterPropertiesSet();
		jobRepository = (JobRepository) factory.getObject();
		job.setJobRepository(jobRepository);
		jobExecution = jobRepository.createJobExecution("job", new JobParameters());
	}

	@Test
	public void testTwoSteps() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		StepExecution stepExecution = getStepExecution(jobExecution, "step2");
		assertEquals(ExitStatus.COMPLETED, stepExecution.getExitStatus());
		assertEquals(2, jobExecution.getStepExecutions().size());
	}

	@Test
	public void testFailedStep() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StateSupport("step1", FlowExecutionStatus.FAILED),
				"step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		StepExecution stepExecution = getStepExecution(jobExecution, "step2");
		assertEquals(ExitStatus.COMPLETED, stepExecution.getExitStatus());
		assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());
		assertEquals(2, jobExecution.getStepExecutions().size());
	}

	@Test
	public void testFailedStepRestarted() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		State step2State = new StateSupport("step2") {
			@Override
			public FlowExecutionStatus handle(FlowExecutor executor) throws Exception {
				JobExecution jobExecution = executor.getJobExecution();
				jobExecution.getStepExecutions().add(new StepExecution(getName(), jobExecution));
				if (fail) {
					return FlowExecutionStatus.FAILED;
				}
				else {
					return FlowExecutionStatus.COMPLETED;
				}
			}
		};
		transitions.add(StateTransition.createStateTransition(step2State, ExitStatus.COMPLETED.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(step2State, ExitStatus.FAILED.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		fail = true;
		job.execute(jobExecution);
		assertEquals(ExitStatus.FAILED, jobExecution.getExitStatus());
		assertEquals(2, jobExecution.getStepExecutions().size());
		jobRepository.update(jobExecution);
		jobExecution = jobRepository.createJobExecution("job", new JobParameters());
		fail = false;
		job.execute(jobExecution);
		assertEquals(ExitStatus.COMPLETED, jobExecution.getExitStatus());
		assertEquals(1, jobExecution.getStepExecutions().size());
	}

	@Test
	public void testStoppingStep() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		State state2 = new StateSupport("step2", FlowExecutionStatus.FAILED);
		transitions.add(StateTransition.createStateTransition(state2, ExitStatus.FAILED.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(state2, ExitStatus.COMPLETED.getExitCode(), "end1"));
		transitions.add(StateTransition.createStateTransition(new EndState(FlowExecutionStatus.STOPPED, "end0"),
				"step3"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end1")));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step3")), "end2"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end2")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		assertEquals(2, jobExecution.getStepExecutions().size());
		assertEquals(BatchStatus.STOPPED, jobExecution.getStatus());
	}

	@Test
	public void testEndStateStopped() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "end"));
		transitions.add(StateTransition
				.createStateTransition(new EndState(FlowExecutionStatus.STOPPED, "end"), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		assertEquals(1, jobExecution.getStepExecutions().size());
		assertEquals(BatchStatus.STOPPED, jobExecution.getStatus());
	}

	public void testEndStateFailed() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "end"));
		transitions
				.add(StateTransition.createStateTransition(new EndState(FlowExecutionStatus.FAILED, "end"), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		assertEquals(BatchStatus.FAILED, jobExecution.getStatus());
		assertEquals(1, jobExecution.getStepExecutions().size());
	}

	@Test
	public void testEndStateStoppedWithRestart() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "end"));
		transitions.add(StateTransition
				.createStateTransition(new EndState(FlowExecutionStatus.STOPPED, "end"), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end1")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();

		// To test a restart we have to use the AbstractJob.execute()...
		job.execute(jobExecution);
		assertEquals(BatchStatus.STOPPED, jobExecution.getStatus());
		assertEquals(1, jobExecution.getStepExecutions().size());

		jobExecution = jobRepository.createJobExecution("job", new JobParameters());
		job.execute(jobExecution);
		assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus());
		assertEquals(1, jobExecution.getStepExecutions().size());

	}

	@Test
	public void testBranching() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "COMPLETED",
				"step3"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end1")));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step3")), ExitStatus.FAILED
				.getExitCode(), "end2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step3")),
				ExitStatus.COMPLETED.getExitCode(), "end3"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end2")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end3")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.afterPropertiesSet();
		job.doExecute(jobExecution);
		StepExecution stepExecution = getStepExecution(jobExecution, "step3");
		assertEquals(ExitStatus.COMPLETED, stepExecution.getExitStatus());
		assertEquals(2, jobExecution.getStepExecutions().size());
	}

	@Test
	public void testBasicFlow() throws Throwable {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step")), "end0"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		flow.setStateTransitions(transitions);
		job.setFlow(flow);
		job.execute(jobExecution);
		if (!jobExecution.getAllFailureExceptions().isEmpty()) {
			throw jobExecution.getAllFailureExceptions().get(0);
		}
		assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus());
	}

	@Test
	public void testDecisionFlow() throws Throwable {

		SimpleFlow flow = new SimpleFlow("job");
		JobExecutionDecider decider = new JobExecutionDecider() {
			public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
				assertNotNull(stepExecution);
				return new FlowExecutionStatus("SWITCH");
			}
		};

		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "decision"));
		transitions.add(StateTransition.createStateTransition(new DecisionState(decider, "decision"), "step2"));
		transitions.add(StateTransition
				.createStateTransition(new DecisionState(decider, "decision"), "SWITCH", "step3"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")),
				ExitStatus.COMPLETED.getExitCode(), "end0"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), ExitStatus.FAILED
				.getExitCode(), "end1"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end1")));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step3")), ExitStatus.FAILED
				.getExitCode(), "end2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step3")),
				ExitStatus.COMPLETED.getExitCode(), "end3"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.FAILED, "end2")));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end3")));
		flow.setStateTransitions(transitions);

		job.setFlow(flow);
		job.doExecute(jobExecution);
		StepExecution stepExecution = getStepExecution(jobExecution, "step3");
		if (!jobExecution.getAllFailureExceptions().isEmpty()) {
			throw jobExecution.getAllFailureExceptions().get(0);
		}

		assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());
		assertEquals(2, jobExecution.getStepExecutions().size());

	}

	@Test
	public void testGetStepExists() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), "end0"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		flow.setStateTransitions(transitions);
		flow.afterPropertiesSet();
		job.setFlow(flow);
		job.afterPropertiesSet();

		Step step = job.getStep("step2");
		assertNotNull(step);
		assertEquals("step2", step.getName());
	}

	@Test
	public void testGetStepNotExists() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), "end0"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		flow.setStateTransitions(transitions);
		flow.afterPropertiesSet();
		job.setFlow(flow);
		job.afterPropertiesSet();

		Step step = job.getStep("foo");
		assertNull(step);
	}

	@Test
	public void testGetStepNotStepState() throws Exception {
		SimpleFlow flow = new SimpleFlow("job");
		List<StateTransition> transitions = new ArrayList<StateTransition>();
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step1")), "step2"));
		transitions.add(StateTransition.createStateTransition(new StepState(new StubStep("step2")), "end0"));
		transitions.add(StateTransition.createEndStateTransition(new EndState(FlowExecutionStatus.COMPLETED, "end0")));
		flow.setStateTransitions(transitions);
		flow.afterPropertiesSet();
		job.setFlow(flow);
		job.afterPropertiesSet();

		Step step = job.getStep("end0");
		assertNull(step);
	}

	/**
	 * @author Dave Syer
	 * 
	 */
	private class StubStep extends StepSupport {

		private StubStep(String name) {
			super(name);
		}

		public void execute(StepExecution stepExecution) throws JobInterruptedException {
			stepExecution.setStatus(BatchStatus.COMPLETED);
			stepExecution.setExitStatus(ExitStatus.COMPLETED);
			jobRepository.update(stepExecution);
		}

	}

	/**
	 * @param jobExecution
	 * @param stepName
	 * @return the StepExecution corresponding to the specified step
	 */
	private StepExecution getStepExecution(JobExecution jobExecution, String stepName) {
		for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
			if (stepExecution.getStepName().equals(stepName)) {
				return stepExecution;
			}
		}
		fail("No stepExecution found with name: [" + stepName + "]");
		return null;
	}

}
