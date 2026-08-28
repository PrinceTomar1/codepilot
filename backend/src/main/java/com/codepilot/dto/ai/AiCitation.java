package com.codepilot.dto.ai;

import java.io.Serializable;

public record AiCitation(String filePath, Integer startLine, Integer endLine, String snippet) implements Serializable {
}
