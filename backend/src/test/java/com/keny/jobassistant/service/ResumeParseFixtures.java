package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试用的模型输出：包含提示词约定的全部字段，能通过 ResumeParseValidator。 */
public final class ResumeParseFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResumeParseFixtures() {
    }

    /** 模型输出的简历 JSON（即 choices[0].message.content 的内容）。 */
    public static String modelOutput(String name, String... skills) {
        ObjectNode output = MAPPER.createObjectNode()
                .put("name", name).put("email", "").put("phone", "").put("location", "").put("summary", "");
        var skillArray = output.putArray("skills");
        for (String skill : skills) skillArray.add(skill);
        for (String section : new String[]{"education", "experience", "projects", "certificates", "languages"}) {
            output.putArray(section);
        }
        return output.toString();
    }

    /** 完整的 Chat Completions 响应体。 */
    public static String chatResponse(String content) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("choices").addObject().putObject("message").put("content", content);
        return body.toString();
    }
}
