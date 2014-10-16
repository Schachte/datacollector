/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.container;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Preconditions;
import com.streamsets.pipeline.api.Module;
import com.streamsets.pipeline.api.Module.Info;
import com.streamsets.pipeline.api.Processor;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Pipeline {

  public static class Builder {
    private boolean built;
    private MetricRegistry metrics;
    private List<Module.Info> modulesInfo;
    private List<Module.Info> modulesInfoRO;
    private List<Pipe> pipes;

    public Builder(MetricRegistry metrics, Module.Info info, Source source, Set<String> output) {
      this(metrics, info, source, output, null);
    }

    public Builder(MetricRegistry metrics, Module.Info info, Source source, Set<String> output, Observer observer) {
      Preconditions.checkNotNull(metrics, "metrics cannot be null");
      Preconditions.checkNotNull(info, "info cannot be null");
      Preconditions.checkNotNull(source, "source cannot be null");
      Preconditions.checkNotNull(output, "output cannot be null");
      Preconditions.checkArgument(!output.isEmpty(), "output cannot be empty");
      this.metrics = metrics;
      modulesInfo = new ArrayList<Info>();
      modulesInfoRO = Collections.unmodifiableList(modulesInfo);
      modulesInfo.add(info);
      pipes = new ArrayList<Pipe>();
      SourcePipe sourcePipe = new SourcePipe(modulesInfoRO, metrics, info, source, output);
      pipes.add(sourcePipe);
      if (observer != null) {
        ObserverPipe observerPipe = new ObserverPipe(sourcePipe, observer);
        pipes.add(observerPipe);
      }
    }

    public Builder add(Module.Info info, Processor processor, Set<String> input, Set<String> output) {
      return add(info, processor, input, output, null);
    }

    public Builder add(Module.Info info, Processor processor, Set<String> input, Set<String> output,
        Observer observer) {
      Preconditions.checkNotNull(info, "info cannot be null");
      Preconditions.checkNotNull(processor, "processor cannot be null");
      Preconditions.checkNotNull(input, "input cannot be null");
      Preconditions.checkNotNull(output, "output cannot be null");
      Preconditions.checkArgument(!input.isEmpty(), "input cannot be empty");
      Preconditions.checkArgument(!output.isEmpty(), "output cannot be empty");
      ProcessorPipe processorPipe = new ProcessorPipe(modulesInfoRO, metrics, info, processor, input, output);
      pipes.add(processorPipe);
      if (observer != null) {
        ObserverPipe observerPipe = new ObserverPipe(processorPipe, observer);
        pipes.add(observerPipe);
      }
      return this;
    }

    public Builder add(Module.Info info, Target target, Set<String> input) {
      Preconditions.checkNotNull(info, "info cannot be null");
      Preconditions.checkNotNull(target, "target cannot be null");
      Preconditions.checkNotNull(input, "input cannot be null");
      Preconditions.checkArgument(!input.isEmpty(), "input cannot be empty");
      pipes.add(new TargetPipe(modulesInfoRO, metrics, info, target, input));
      return this;
    }

    public Builder validate() {
      Pipeline.validate(pipes.toArray(new Pipe[pipes.size()]));
      return this;
    }

    public Pipeline build() {
      Preconditions.checkState(!built, "Builder has been built already, it cannot be reused");
      Pipeline pipeline = new Pipeline(pipes.toArray(new Pipe[pipes.size()]));
      built = true;
      return pipeline;
    }

  }

  private Pipe[] pipes;
  private boolean inited;
  private boolean destroyed;

  private Pipeline(Pipe[] pipes) {
    validate(pipes);
    this.pipes = pipes;
  }

  private static void validate(Pipe[] pipes) {
    Preconditions.checkNotNull(pipes, "pipes cannot be null");
    Set<String> moduleNames = new HashSet<String>();
    Set<String> currentLines = new HashSet<String>();
    for (Pipe pipe : pipes) {
      Preconditions.checkState(!moduleNames.contains(pipe.getModuleInfo().getInstanceName()), String.format(
          "Pipe '%s' already exists", pipe.getModuleInfo().getInstanceName()));
      moduleNames.add(pipe.getModuleInfo().getInstanceName());
      Preconditions.checkState(currentLines.containsAll(pipe.getInputLanes()), String.format(
          "Pipe '%s' requires a input line which is not available", pipe.getModuleInfo().getInstanceName()));
      currentLines.removeAll(pipe.getConsumedLanes());
      currentLines.addAll(pipe.getOutputLanes());
    }
    Preconditions.checkState(currentLines.isEmpty(), String.format(
        "End of pipeline should not have any line, it has: %s", currentLines));
  }

  public synchronized void init() {
    Preconditions.checkState(!inited, "Pipeline has been already initialized");
    inited = true;
    for (Pipe pipe : pipes) {
      pipe.init();
    }
  }

  public synchronized void destroy() {
    Preconditions.checkState(inited, "Pipeline has not been initialized");
    if (!destroyed) {
      destroyed = true;
      for (int i = pipes.length - 1; i >= 0; i--) {
        pipes[i].destroy();
      }
    }
  }

  public synchronized void configure(Configuration conf) {
    Preconditions.checkState(inited, "pipeline must be initialized");
    Preconditions.checkState(!destroyed, "pipeline has been destroyed");
    Preconditions.checkNotNull(conf, "conf cannot be null");

    // configure pipeline
    Configuration pipelineConf = conf.getSubSetConfiguration("pipeline.");
    // TODO

    // configuring pipes
    for (Pipe pipe : pipes) {
      pipe.configure(conf.getSubSetConfiguration(pipe.getModuleInfo().getInstanceName()));
    }
  }

  public synchronized void runBatch(PipelineBatch batch) {
    Preconditions.checkState(inited, "pipeline must be initialized");
    Preconditions.checkState(!destroyed, "pipeline has been destroyed");
    Preconditions.checkNotNull(batch, "batch cannot be null");
    for (Pipe pipe : pipes) {
      batch.createLines(pipe.getOutputLanes());
      pipe.processBatch(batch);
      batch.deleteLines(pipe.getConsumedLanes());
    }
    Preconditions.checkState(batch.isEmpty(), String.format("Batch should be empty, it has: %s", batch.getLanes()));
  }

}
