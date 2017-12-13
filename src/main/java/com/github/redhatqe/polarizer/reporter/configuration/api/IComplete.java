package com.github.redhatqe.polarizer.reporter.configuration.api;

import java.util.List;

public interface IComplete<R> {
    Integer completed();
    void addToComplete(R s);
    List<R> getCompleted();
    void setMapping(R res);
}
