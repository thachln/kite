/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kitesdk.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.Target;
import org.kitesdk.data.View;
import org.kitesdk.tools.CopyTask;
import org.slf4j.Logger;

import static org.apache.avro.generic.GenericData.Record;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.thrift.DelegationTokenIdentifier;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.security.token.Token;
import org.apache.thrift.TException;

@Parameters(commandDescription="Copy records from one Dataset to another")
public class CopyCommand extends BaseDatasetCommand {

  public CopyCommand(Logger console) {
    super(console);
  }

  @Parameter(description="<source dataset> <destination dataset>")
  List<String> datasets;

  @Parameter(names={"--no-compaction"},
      description="Copy to output directly, without compacting the data")
  boolean noCompaction = false;

  @Parameter(names={"--num-writers"},
      description="The number of writer processes to use")
  int numWriters = -1;

  @Parameter(
      names={"--overwrite"},
      description="Remove any data already in the target view or dataset")
  boolean overwrite = false;

  @Override
  public int run() throws IOException {
    Preconditions.checkArgument(datasets != null && datasets.size() > 1,
        "Source and target datasets are required");
    Preconditions.checkArgument(datasets.size() == 2,
        "Cannot copy multiple datasets");

    View<Record> source = load(datasets.get(0), Record.class);
    View<Record> dest = load(datasets.get(1), Record.class);

    CopyTask task = new CopyTask<Record>(source, dest);

    JobConf conf = new JobConf(getConf());

    try {
      if ((isHiveView(source) || isHiveView(dest))
          && conf.getBoolean(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL.varname, false)) {
        // Need to set up delegation token auth
        HiveMetaStoreClient metaStoreClient = new HiveMetaStoreClient(new HiveConf());
        String hiveTokenStr = metaStoreClient.getDelegationToken("yarn");
        Token<DelegationTokenIdentifier> hiveToken = new Token<DelegationTokenIdentifier>();
        hiveToken.decodeFromUrlString(hiveTokenStr);
        conf.getCredentials().addToken(new Text("HIVE_METASTORE_TOKEN"), hiveToken);
    }
    } catch (TException ex) {
      throw new RuntimeException("Unable to obtain Hive delegation token");
    }

    task.setConf(conf);

    if (noCompaction) {
      task.noCompaction();
    }

    if (numWriters >= 0) {
      task.setNumWriters(numWriters);
    }

    if (overwrite) {
      task.setWriteMode(Target.WriteMode.OVERWRITE);
    }

    PipelineResult result = task.run();

    if (result.succeeded()) {
      console.info("Added {} records to \"{}\"",
          task.getCount(), datasets.get(1));
      return 0;
    } else {
      return 1;
    }
  }

  @Override
  public List<String> getExamples() {
    return Lists.newArrayList(
        "# Copy the contents of movies_avro to movies_parquet",
        "movies_avro movies_parquet",
        "# Copy the movies dataset into HBase in a map-only job",
        "movies dataset:hbase:zk-host/movies --no-compaction"
    );
  }

  private boolean isHiveView(View<Record> view) {
    return view.getDataset().getUri().toString().startsWith("dataset:hive:");
  }
}