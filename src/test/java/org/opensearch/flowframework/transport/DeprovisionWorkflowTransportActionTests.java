/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.flowframework.transport;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.client.Client;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.flowframework.common.FlowFrameworkSettings;
import org.opensearch.flowframework.indices.FlowFrameworkIndicesHandler;
import org.opensearch.flowframework.model.ResourceCreated;
import org.opensearch.flowframework.model.WorkflowState;
import org.opensearch.flowframework.workflow.CreateConnectorStep;
import org.opensearch.flowframework.workflow.DeleteConnectorStep;
import org.opensearch.flowframework.workflow.ProcessNode;
import org.opensearch.flowframework.workflow.WorkflowData;
import org.opensearch.flowframework.workflow.WorkflowStepFactory;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.FixedExecutorBuilder;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.junit.AfterClass;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;

import static org.opensearch.flowframework.common.CommonValue.FLOW_FRAMEWORK_THREAD_POOL_PREFIX;
import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_THREAD_POOL;
import static org.opensearch.flowframework.common.WorkflowResources.CONNECTOR_ID;
import static org.opensearch.flowframework.common.WorkflowResources.MODEL_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DeprovisionWorkflowTransportActionTests extends OpenSearchTestCase {

    private static ThreadPool threadPool = new TestThreadPool(
        DeprovisionWorkflowTransportActionTests.class.getName(),
        new FixedExecutorBuilder(
            Settings.EMPTY,
            WORKFLOW_THREAD_POOL,
            OpenSearchExecutors.allocatedProcessors(Settings.EMPTY),
            100,
            FLOW_FRAMEWORK_THREAD_POOL_PREFIX + WORKFLOW_THREAD_POOL
        )
    );
    private Client client;
    private WorkflowStepFactory workflowStepFactory;
    private DeleteConnectorStep deleteConnectorStep;
    private DeprovisionWorkflowTransportAction deprovisionWorkflowTransportAction;
    private FlowFrameworkIndicesHandler flowFrameworkIndicesHandler;
    private FlowFrameworkSettings flowFrameworkSettings;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        this.client = mock(Client.class);
        this.workflowStepFactory = mock(WorkflowStepFactory.class);
        this.flowFrameworkIndicesHandler = mock(FlowFrameworkIndicesHandler.class);
        flowFrameworkSettings = mock(FlowFrameworkSettings.class);
        when(flowFrameworkSettings.getRequestTimeout()).thenReturn(TimeValue.timeValueSeconds(10));

        this.deprovisionWorkflowTransportAction = new DeprovisionWorkflowTransportAction(
            mock(TransportService.class),
            mock(ActionFilters.class),
            threadPool,
            client,
            workflowStepFactory,
            flowFrameworkIndicesHandler,
            flowFrameworkSettings
        );

        MachineLearningNodeClient mlClient = new MachineLearningNodeClient(client);
        ProcessNode processNode = mock(ProcessNode.class);
        when(processNode.id()).thenReturn("step_1");
        when(processNode.workflowStep()).thenReturn(new CreateConnectorStep(mlClient, flowFrameworkIndicesHandler));
        when(processNode.previousNodeInputs()).thenReturn(Collections.emptyMap());
        when(processNode.input()).thenReturn(WorkflowData.EMPTY);
        when(processNode.nodeTimeout()).thenReturn(TimeValue.timeValueSeconds(5));
        this.deleteConnectorStep = mock(DeleteConnectorStep.class);
        when(this.workflowStepFactory.createStep("delete_connector")).thenReturn(deleteConnectorStep);

        ThreadPool clientThreadPool = mock(ThreadPool.class);
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);

        when(client.threadPool()).thenReturn(clientThreadPool);
        when(clientThreadPool.getThreadContext()).thenReturn(threadContext);
    }

    @AfterClass
    public static void cleanup() {
        ThreadPool.terminate(threadPool, 500, TimeUnit.MILLISECONDS);
    }

    public void testDeprovisionWorkflow() throws IOException {
        String workflowId = "1";
        @SuppressWarnings("unchecked")
        ActionListener<WorkflowResponse> listener = mock(ActionListener.class);
        WorkflowRequest workflowRequest = new WorkflowRequest(workflowId, null);

        doAnswer(invocation -> {
            ActionListener<GetWorkflowStateResponse> responseListener = invocation.getArgument(2);

            WorkflowState state = WorkflowState.builder()
                .resourcesCreated(List.of(new ResourceCreated("create_connector", "step_1", CONNECTOR_ID, "connectorId")))
                .build();
            responseListener.onResponse(new GetWorkflowStateResponse(state, true));
            return null;
        }).when(client).execute(any(GetWorkflowStateAction.class), any(GetWorkflowStateRequest.class), any());

        when(this.deleteConnectorStep.execute(anyString(), any(WorkflowData.class), anyMap(), anyMap())).thenReturn(
            CompletableFuture.completedFuture(WorkflowData.EMPTY)
        );

        deprovisionWorkflowTransportAction.doExecute(mock(Task.class), workflowRequest, listener);
        ArgumentCaptor<WorkflowResponse> responseCaptor = ArgumentCaptor.forClass(WorkflowResponse.class);

        verify(listener, times(1)).onResponse(responseCaptor.capture());
        assertEquals(workflowId, responseCaptor.getValue().getWorkflowId());
    }

    public void testFailToDeprovision() throws IOException {
        String workflowId = "1";
        @SuppressWarnings("unchecked")
        ActionListener<WorkflowResponse> listener = mock(ActionListener.class);
        WorkflowRequest workflowRequest = new WorkflowRequest(workflowId, null);

        doAnswer(invocation -> {
            ActionListener<GetWorkflowStateResponse> responseListener = invocation.getArgument(2);

            WorkflowState state = WorkflowState.builder()
                .resourcesCreated(List.of(new ResourceCreated("deploy_model", "step_1", MODEL_ID, "modelId")))
                .build();
            responseListener.onResponse(new GetWorkflowStateResponse(state, true));
            return null;
        }).when(client).execute(any(GetWorkflowStateAction.class), any(GetWorkflowStateRequest.class), any());

        CompletableFuture<WorkflowData> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("rte"));
        when(this.deleteConnectorStep.execute(anyString(), any(WorkflowData.class), anyMap(), anyMap())).thenReturn(future);

        deprovisionWorkflowTransportAction.doExecute(mock(Task.class), workflowRequest, listener);
        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);

        verify(listener, times(1)).onFailure(exceptionCaptor.capture());
        assertEquals("Failed to deprovision some resources: [model_id modelId].", exceptionCaptor.getValue().getMessage());
    }
}
