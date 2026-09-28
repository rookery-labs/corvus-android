package dev.corvus.browser

import dev.corvus.browser.rpc.JsonRpcValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonRpcValidationTest {

    @Test
    fun testValidDomObservationRequestParsing() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "req-101",
                "method": "dom.observation",
                "params": {
                    "maxDepth": 16
                }
            }
        """.trimIndent()

        val result = JsonRpcValidator.parseAndValidateRequest(json)
        assertTrue(result.isSuccess)
        val req = result.getOrThrow()
        assertEquals("2.0", req.jsonrpc)
        assertEquals("dom.observation", req.method)
        assertEquals("req-101", req.id)
    }

    @Test
    fun testRejectInvalidJsonRpcVersion() {
        val json = """
            {
                "jsonrpc": "1.0",
                "id": "req-102",
                "method": "action.execute"
            }
        """.trimIndent()

        val result = JsonRpcValidator.parseAndValidateRequest(json)
        assertTrue(result.isFailure)
    }

    @Test
    fun testParseSuccessfulResponse() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "req-103",
                "result": {
                    "success": true,
                    "nodeId": 42
                }
            }
        """.trimIndent()

        val result = JsonRpcValidator.parseResponse(json)
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertNotNull(resp.result)
        assertNull(resp.error)
    }

    @Test
    fun testParseErrorResponse() {
        val json = """
            {
                "jsonrpc": "2.0",
                "id": "req-104",
                "error": {
                    "code": -32601,
                    "message": "Method not found"
                }
            }
        """.trimIndent()

        val result = JsonRpcValidator.parseResponse(json)
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertNull(resp.result)
        assertNotNull(resp.error)
        assertEquals(-32601, resp.error?.code)
    }
}
