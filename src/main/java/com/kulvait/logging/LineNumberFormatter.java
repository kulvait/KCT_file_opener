package com.kulvait.logging;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class LineNumberFormatter extends Formatter {

    @Override
    public String format(LogRecord record) {
        String source = record.getSourceClassName();
        String className = source != null ? source.substring(source.lastIndexOf(".") + 1) : record.getLoggerName();
        String methodName = record.getSourceMethodName() != null ? record.getSourceMethodName() : "unknown";

        // Get line number from stack trace
        int lineNumber = -1;
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement ste : stackTrace) {
            if (ste.getClassName().equals(record.getSourceClassName()) && ste.getMethodName().equals(
                    record.getSourceMethodName())) {
                lineNumber = ste.getLineNumber();
                break;
            }
        }

        return String.format("%s.%s():%d - %s%n",
                className,
                methodName,
                lineNumber,
                record.getMessage());
    }
}
