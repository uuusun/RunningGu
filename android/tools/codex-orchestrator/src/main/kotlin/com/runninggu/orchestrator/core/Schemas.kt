package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.util.OrchestratorJson
import kotlinx.serialization.json.JsonObject

object Schemas {
    val plan: JsonObject = schema(
        """{
          "type":"object",
          "properties":{
            "planId":{"type":"string"},
            "summary":{"type":"string"},
            "tasks":{"type":"array","items":{
              "type":"object",
              "properties":{
                "id":{"type":"string"},
                "goal":{"type":"string"},
                "dependencies":{"type":"array","items":{"type":"string"}},
                "readFiles":{"type":"array","items":{"type":"string"}},
                "writableFiles":{"type":"array","items":{"type":"string"}},
                "completionCriteria":{"type":"array","items":{"type":"string"}},
                "difficulty":{"type":"string","enum":["LOW","MEDIUM","HIGH"]}
              },
              "required":["id","goal","dependencies","readFiles","writableFiles","completionCriteria","difficulty"],
              "additionalProperties":false
            }}
          },
          "required":["planId","summary","tasks"],
          "additionalProperties":false
        }""",
    )

    val workerResult: JsonObject = schema(
        """{
          "type":"object",
          "properties":{
            "taskId":{"type":"string"},
            "status":{"type":"string","enum":["SUCCESS","FAILED","BLOCKED"]},
            "summary":{"type":"string","maxLength":4000},
            "changedFiles":{"type":"array","maxItems":500,"items":{"type":"string","maxLength":1000}},
            "tests":{"type":"array","items":{"type":"object","properties":{
              "command":{"type":"string","maxLength":2000},"outcome":{"type":"string","maxLength":8000}
            },"required":["command","outcome"],"additionalProperties":false}},
            "criteriaMet":{"type":"boolean"},
            "risks":{"type":"array","maxItems":50,"items":{"type":"string","maxLength":4000}},
            "commitOrPatch":{"type":["string","null"],"maxLength":1000},
            "failureReason":{"type":["string","null"],"maxLength":8000}
          },
          "required":["taskId","status","summary","changedFiles","tests","criteriaMet","risks","commitOrPatch","failureReason"],
          "additionalProperties":false
        }""",
    )

    val review: JsonObject = schema(
        """{
          "type":"object",
          "properties":{
            "summary":{"type":"string"},
            "decisions":{"type":"array","items":{"type":"object","properties":{
              "taskId":{"type":"string"},
              "accepted":{"type":"boolean"},
              "feedback":{"type":"string"}
            },"required":["taskId","accepted","feedback"],"additionalProperties":false}}
          },
          "required":["summary","decisions"],
          "additionalProperties":false
        }""",
    )

    private fun schema(raw: String): JsonObject = OrchestratorJson.parseToJsonElement(raw) as JsonObject
}
