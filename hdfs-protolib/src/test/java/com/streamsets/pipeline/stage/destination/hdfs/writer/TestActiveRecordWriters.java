/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.streamsets.pipeline.stage.destination.hdfs.writer;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.OnRecordError;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorException;
import com.streamsets.pipeline.sdk.ContextInfoCreator;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.stage.destination.hdfs.HdfsDTarget;
import com.streamsets.pipeline.stage.destination.hdfs.HdfsFileType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

public class TestActiveRecordWriters {
  private Path testDir;

  public static class DummyDataGeneratorFactory extends DataGeneratorFactory {
    protected DummyDataGeneratorFactory(Settings settings) {
      super(settings);
    }

    @Override
    public DataGenerator getGenerator(OutputStream os) throws IOException {
      return new DataGenerator() {
        @Override
        public void write(Record record) throws IOException, DataGeneratorException {
        }

        @Override
        public void flush() throws IOException {

        }

        @Override
        public void close() throws IOException {

        }
      };
    }
  }

  @Before
  public void setUpClass() {
    File dir = new File("target", UUID.randomUUID().toString()).getAbsoluteFile();
    Assert.assertTrue(dir.mkdirs());
    testDir = new Path(dir.getAbsolutePath());
  }

  private Path getTestDir() {
    return testDir;
  }

  @Test
  public void testWritersLifecycle() throws Exception {
    RecordWriterManager mgr = new RecordWriterManagerTestBuilder()
      .context(ContextInfoCreator.createTargetContext(HdfsDTarget.class, "testWritersLifecycle", false, OnRecordError.TO_ERROR, null))
      .dirPathTemplate(getTestDir().toString() + "/${YYYY()}/${MM()}/${DD()}/${hh()}/${mm()}/${ss()}/${record:value('/')}")
      .build();

    ActiveRecordWriters writers = new ActiveRecordWriters(mgr);

    Date now = new Date();

    // record older than cut off
    Date recordDate = new Date(now.getTime() - 3 * 1000 - 1);
    Record record = RecordCreator.create();
    record.set(Field.create("a"));
    Assert.assertNull(writers.get(now, recordDate, record));

    recordDate = new Date(now.getTime());
    RecordWriter writer = writers.get(now, recordDate, record);
    Assert.assertNotNull(writer);
    Path tempPath = writer.getPath();
    writer.write(record);
    writers.release(writer, false);
    //writer should still be open
    Assert.assertFalse(writer.isClosed());

    writer = writers.get(now, recordDate, record);
    writer.write(record);
    writers.release(writer, false);
    //writer should be close because of going over record count threshold
    Assert.assertTrue(writer.isClosed());

    //we should be able to get a new writer as the cutoff didn't kick in yet
    writer = writers.get(now, recordDate, record);
    Assert.assertNotNull(writer);
    writers.purge();
    //purging should not close the writer as the cutoff didn't kick in yet
    Assert.assertFalse(writer.isClosed());

    Thread.sleep(3001);
    writers.purge();
    //purging should  close the writer as the cutoff kicked in yet
    Assert.assertTrue(writer.isClosed());

    //verifying closeAll() closes writers
    writer = writers.get(new Date(), new Date(), record);
    Assert.assertNotNull(writer);
    writers.closeAll();
    Assert.assertTrue(writer.isClosed());
  }

  @Test
  public void testRenameOnIdle() throws Exception {
    RecordWriterManager mgr = new RecordWriterManagerTestBuilder()
      .context(ContextInfoCreator.createTargetContext(HdfsDTarget.class, "testWritersLifecycle", false, OnRecordError.TO_ERROR, null))
      .dirPathTemplate(getTestDir().toString())
      .build();

    mgr.setIdleTimeoutSeconds(1L);
    ActiveRecordWriters writers = new ActiveRecordWriters(mgr);

    Date now = new Date();

    Record record = RecordCreator.create();

    RecordWriter writer = writers.get(now, now, record);
    Assert.assertNotNull(writer);
    writer.write(record);
    writer.flush();
    //writer should still be open
    Assert.assertFalse(writer.isClosed());

    Thread.sleep(1500);
    Assert.assertTrue(writer.isClosed());
    File[] files = new File(getTestDir().toString()).listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith("prefix");
      }
    });
    Assert.assertEquals(1, files.length);
    files = new File(getTestDir().toString()).listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith("_tmp_");
      }
    });
    Assert.assertEquals(0, files.length);
  }

}
