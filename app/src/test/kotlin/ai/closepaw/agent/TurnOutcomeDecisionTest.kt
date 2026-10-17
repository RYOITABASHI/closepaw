package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.cognition.policy.TurnToolPolicy
import ai.closepaw.tool.ToolCallResult
import org.json.JSONObject
import org.junit.Test

class TurnOutcomeDecisionTest {
    private val policy = TurnToolPolicy()

    @Test
    fun `complete_task executed normally yields Complete`() {
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(complete.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(complete.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((outcome as TurnOutcome.Complete).message).isEqualTo("done")
        assertThat(outcome.success).isTrue()
    }

    @Test
    fun `failure before complete_task yields Error not Complete`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = true,
            lastTerminalResult = ToolCallResult.Error(screen.id, "tap missed target")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Error::class.java)
        assertThat((outcome as TurnOutcome.Error).message).isEqualTo("tap missed target")
    }

    @Test
    fun `cancellation before complete_task yields Cancelled`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = true,
            lastTerminalResult = ToolCallResult.Cancelled(screen.id, "user")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Cancelled)
    }

    @Test
    fun `no complete_task planned and tools succeed yields Continue`() {
        val screen = toolCall("mobile_action")
        val calls = listOf(screen)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = false)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(screen.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Continue)
    }

    @Test
    fun `complete_task planned but not executed yields Error`() {
        // Pathological: complete_task was planned and wasn't dropped by arbitration,
        // but somehow never executed. Treat as Error, not Complete.
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = emptySet(),
            terminatedEarly = false,
            lastTerminalResult = null
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Error::class.java)
    }

    @Test
    fun `complete_task dropped by arbitration due to screen action yields Continue`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        // Sanity: arbitration dropped complete_task because of the screen action.
        assertThat(arbitration.selectedToolCalls.map { it.id }).doesNotContain(complete.id)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(screen.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Continue)
    }

    @Test
    fun `complete_task with status failure yields Complete with success=false`() {
        val complete = toolCall(
            "complete_task",
            JSONObject("""{"status":"failure","answer":"cannot do it"}""")
        )
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(complete.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(complete.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((outcome as TurnOutcome.Complete).success).isFalse()
    }

    private fun toolCall(name: String, arguments: JSONObject = JSONObject()): ToolCallRequest {
        return ToolCallRequest(id = "call-$name", name = name, arguments = arguments)
    }

    @Test
    fun `completion gate turns a refused complete_task into Continue with a rejection`() {
        val gated = TurnToolPolicy(completionGate = { _, answer ->
            if (answer.contains("10 of 10")) null else "NOT FINISHED: 1 of 10"
        })
        val complete = toolCall("complete_task", JSONObject("""{"status":"success","answer":"added Jeremy"}"""))
        val calls = listOf(complete)
        val arbitration = gated.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(complete.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(complete.id, "ok")
        )

        assertThat(decideTurnOutcome(gated, turnResult, arbitration, execution)).isEqualTo(TurnOutcome.Continue)
        assertThat(gated.decideCompletion(turnResult, arbitration).rejection).isEqualTo("NOT FINISHED: 1 of 10")

        val done = toolCall("complete_task", JSONObject("""{"status":"success","answer":"added 10 of 10"}"""))
        val doneCalls = listOf(done)
        val doneOutcome = decideTurnOutcome(
            gated,
            TurnResult(content = null, toolCalls = doneCalls, isComplete = true),
            gated.arbitrateToolCalls(doneCalls),
            ExecutionPhaseResult(setOf(done.id), false, ToolCallResult.Success(done.id, "ok"))
        )
        assertThat(doneOutcome).isInstanceOf(TurnOutcome.Complete::class.java)

        // A declared failure is never gated: the agent may give up with an explanation.
        val failed = toolCall("complete_task", JSONObject("""{"status":"failure","answer":"blocked"}"""))
        val failedCalls = listOf(failed)
        val failedOutcome = decideTurnOutcome(
            gated,
            TurnResult(content = null, toolCalls = failedCalls, isComplete = true),
            gated.arbitrateToolCalls(failedCalls),
            ExecutionPhaseResult(setOf(failed.id), false, ToolCallResult.Success(failed.id, "ok"))
        )
        assertThat(failedOutcome).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((failedOutcome as TurnOutcome.Complete).success).isFalse()
    }

    @Test
    fun `after the rejection budget the task ends as a failure, never a success`() {
        val gated = TurnToolPolicy(completionGate = { _, _ -> "NOT FINISHED: observed 1 of 3" }, maxGateRejections = 2)
        fun attempt(): TurnOutcome {
            val call = toolCall("complete_task", JSONObject("""{"status":"success","answer":"3 of 3"}"""))
            val calls = listOf(call)
            return decideTurnOutcome(
                gated,
                TurnResult(content = null, toolCalls = calls, isComplete = true),
                gated.arbitrateToolCalls(calls),
                ExecutionPhaseResult(setOf(call.id), false, ToolCallResult.Success(call.id, "ok"))
            )
        }
        assertThat(attempt()).isEqualTo(TurnOutcome.Continue)
        assertThat(attempt()).isEqualTo(TurnOutcome.Continue)
        val last = attempt()
        assertThat(last).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((last as TurnOutcome.Complete).success).isFalse()
        assertThat(last.message).contains("Not verified")
    }
}
