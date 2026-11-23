package ai.closepaw.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStopClassificationTest {
    @Test fun plainStopCall() {
        assertTrue(McpStopClassification.isStopCall("""{"method":"tools/call","params":{"name":"stop_run"}}"""))
    }

    @Test fun unquotedStopNameAcceptedByDecoder() {
        assertTrue(McpStopClassification.isStopCall("""{"method":"tools/call","params":{"name":stop_run}}"""))
    }

    @Test fun escapedMethodKeyAndToolName() {
        assertTrue(McpStopClassification.isStopCall("""{"meth\u006fd":"tools/call","params":{"name":"stop\u005frun"}}"""))
    }

    @Test fun reorderedKeysAndWhitespace() {
        assertTrue(McpStopClassification.isStopCall("""
            {
                "params": { "name" : "stop_run" },
                "method" : "tools/call"
            }
        """.trimIndent()))
    }

    @Test fun stopNameInTextArgument() {
        assertFalse(McpStopClassification.isStopCall(
            """{"method":"tools/call","params":{"name":"type_text","arguments":{"text":"\"name\": \"stop_run\""}}}""",
        ))
    }

    @Test fun stopNameInGoal() {
        assertFalse(McpStopClassification.isStopCall(
            """{"method":"tools/call","params":{"name":"start_session","arguments":{"goal":"mention stop_run"}}}""",
        ))
    }

    @Test fun stopNameInNestedParams() {
        assertFalse(McpStopClassification.isStopCall(
            """{"method":"tools/call","params":{"name":"observe","arguments":{"params":{"name":"stop_run"}}}}""",
        ))
    }

    @Test fun stopNameDirectlyInRegularCallArguments() {
        assertFalse(McpStopClassification.isStopCall(
            """{"method":"tools/call","params":{"name":"start_session","arguments":{"name":"stop_run"}}}""",
        ))
    }

    @Test fun unquotedMethodIsRejectedLikeHandlerDispatch() {
        assertFalse(McpStopClassification.isStopCall("""{"method":tools/call,"params":{"name":"stop_run"}}"""))
    }

    @Test fun wrongMethod() {
        assertFalse(McpStopClassification.isStopCall("""{"method":"tools/list","params":{"name":"stop_run"}}"""))
    }

    @Test fun malformedJson() {
        assertFalse(McpStopClassification.isStopCall("""{"method":"tools/call","params":{"name":"stop_run"}"""))
    }

    @Test fun arrayRoot() {
        assertFalse(McpStopClassification.isStopCall("""[{"method":"tools/call","params":{"name":"stop_run"}}]"""))
    }

    @Test fun unsupportedNameValues() {
        for (name in listOf("null", "42", "true", """["stop_run"]""", """{"name":"stop_run"}""")) {
            assertFalse(McpStopClassification.isStopCall("""{"method":"tools/call","params":{"name":$name}}"""))
        }
    }

    @Test fun missingFieldsAndWrongParamsType() {
        assertFalse(McpStopClassification.isStopCall("""{"params":{"name":"stop_run"}}"""))
        assertFalse(McpStopClassification.isStopCall("""{"method":"tools/call","params":{}}"""))
        assertFalse(McpStopClassification.isStopCall("""{"method":"tools/call","params":["stop_run"]}"""))
    }
}
