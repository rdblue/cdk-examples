/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.examples.demo;

import com.cloudera.cdk.data.Dataset;
import com.cloudera.cdk.data.DatasetRepositories;
import com.cloudera.cdk.data.DatasetRepository;
import com.cloudera.cdk.data.PartitionKey;
import com.cloudera.cdk.data.crunch.CrunchDatasets;
import com.cloudera.cdk.data.event.StandardEvent;
import com.cloudera.cdk.data.filesystem.FileSystemDatasetRepository;
import com.cloudera.cdk.examples.demo.event.Session;
import com.google.common.collect.Iterables;
import java.io.Serializable;
import java.net.URI;
import java.util.Iterator;
import org.apache.crunch.DoFn;
import org.apache.crunch.Emitter;
import org.apache.crunch.MapFn;
import org.apache.crunch.PCollection;
import org.apache.crunch.Pair;
import org.apache.crunch.Target;
import org.apache.crunch.types.avro.Avros;
import org.apache.crunch.util.CrunchTool;
import org.apache.hadoop.util.ToolRunner;

public class CreateSessions extends CrunchTool implements Serializable {

  @Override
  public int run(String[] args) throws Exception {

    // Construct a local filesystem dataset repository rooted at /tmp/data
    DatasetRepository fsRepo = DatasetRepositories.open("repo:hdfs:/tmp/data");

    // Construct an HCatalog dataset repository using external Hive tables
    DatasetRepository hcatRepo = DatasetRepositories.open("repo:hive:/tmp/data");

    // Turn debug on while in development.
    getPipeline().enableDebug();
    getPipeline().getConfiguration().set("crunch.log.job.progress", "true");

    // Load the events dataset and get the correct partition to sessionize
    Dataset eventsDataset = fsRepo.load("events");
    Dataset partition;
    if (args.length == 0 || (args.length == 1 && args[0].equals("LATEST"))) {
      partition = getLatestPartition(eventsDataset);
    } else {
      partition = getPartitionForURI(eventsDataset, args[0]);
    }

    // Create a parallel collection from the working partition
    PCollection<StandardEvent> events = read(
        CrunchDatasets.asSource(partition, StandardEvent.class));

    // Group events by user and cookie id, then create a session for each group
    PCollection<Session> sessions = events
        .by(new GetSessionKey(), Avros.strings())
        .groupByKey()
        .parallelDo(new MakeSession(), Avros.specifics(Session.class));

    // Write the sessions to the "sessions" Dataset
    getPipeline().write(sessions, CrunchDatasets.asTarget(hcatRepo.load("sessions")),
        Target.WriteMode.APPEND);

    return run().succeeded() ? 0 : 1;
  }

  private static class GetSessionKey extends MapFn<StandardEvent, String> {
    @Override
    public String map(StandardEvent event) {
      // Create a key from the session id and user id
      return event.getSessionId() + event.getUserId();
    }
  }

  private static class MakeSession
      extends DoFn<Pair<String, Iterable<StandardEvent>>, Session> {

    @Override
    public void process(
        Pair<String, Iterable<StandardEvent>> keyAndEvents,
        Emitter<Session> emitter) {
      final Iterator<StandardEvent> events = keyAndEvents.second().iterator();
      if (!events.hasNext()) {
        return;
      }

      // Initialize the values needed to create a session for this group
      final StandardEvent firstEvent = events.next();
      long startTime = firstEvent.getTimestamp();
      long endTime = firstEvent.getTimestamp();
      int numEvents = 1;

      // Inspect each event and keep track of start time, end time, and count
      while (events.hasNext()) {
        final StandardEvent event = events.next();
        startTime = Math.min(startTime, event.getTimestamp());
        endTime = Math.max(endTime, event.getTimestamp());
        numEvents += 1;
      }

      // Create a session. Use the first event for fields that do not change
      emitter.emit(Session.newBuilder()             // same on all events:
          .setUserId(firstEvent.getUserId())        // the user id (grouped by)
          .setSessionId(firstEvent.getSessionId())  // session id (grouped by)
          .setIp(firstEvent.getIp())                // the source IP address
          .setStartTimestamp(startTime)
          .setDuration(endTime - startTime)
          .setSessionEventCount(numEvents)
          .build());
    }
  }

  private Dataset getLatestPartition(Dataset eventsDataset) {
    Dataset ds = eventsDataset;
    while (ds.getDescriptor().isPartitioned()) {
      ds = Iterables.getLast(ds.getPartitions());
    }
    return ds;
  }

  private Dataset getPartitionForURI(Dataset eventsDataset, String uri) {
    PartitionKey partitionKey = FileSystemDatasetRepository.partitionKeyForPath(
        eventsDataset, URI.create(uri));
    Dataset partition = eventsDataset.getPartition(partitionKey, false);
    if (partition == null) {
      throw new IllegalArgumentException("Partition not found: " + uri);
    }
    return partition;
  }

  public static void main(String... args) throws Exception {
    int rc = ToolRunner.run(new CreateSessions(), args);
    System.exit(rc);
  }

}
