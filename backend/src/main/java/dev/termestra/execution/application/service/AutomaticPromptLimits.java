package dev.termestra.execution.application.service;

import dev.termestra.execution.application.exception.ExecutionConflict;

/** Owns the final allocation and delivery budget for text injected into an agent terminal. */
final class AutomaticPromptLimits {
    static final int MAX_AUTOMATIC_PROMPT_CHARACTERS=131_072;
    private static final int RECOVERY_TAIL_CHARACTERS=8_192;
    private static final String TRUNCATION_NOTICE="\n\n[Termestra：恢复上下文已按安全上限截断]\n";

    private AutomaticPromptLimits(){ }

    static String requireWithinLimit(String prompt){
        if(prompt.length()>MAX_AUTOMATIC_PROMPT_CHARACTERS){
            throw new ExecutionConflict("Automatic terminal prompt exceeds "+
                    MAX_AUTOMATIC_PROMPT_CHARACTERS+" characters");
        }
        return prompt;
    }

    static String boundedRecovery(String prompt){
        if(prompt.length()<=MAX_AUTOMATIC_PROMPT_CHARACTERS)return prompt;
        int tailStart=prompt.length()-RECOVERY_TAIL_CHARACTERS;
        if(tailStart<prompt.length()&&Character.isLowSurrogate(prompt.charAt(tailStart)))tailStart++;
        int headEnd=MAX_AUTOMATIC_PROMPT_CHARACTERS-TRUNCATION_NOTICE.length()
                -(prompt.length()-tailStart);
        if(headEnd>0&&Character.isHighSurrogate(prompt.charAt(headEnd-1)))headEnd--;
        return prompt.substring(0,headEnd)+TRUNCATION_NOTICE+prompt.substring(tailStart);
    }
}
