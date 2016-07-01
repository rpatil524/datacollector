/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.lib.hive;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.lib.parser.shaded.com.google.code.regexp.Matcher;
import com.streamsets.pipeline.lib.parser.shaded.com.google.code.regexp.Pattern;
import com.streamsets.pipeline.stage.lib.hive.cache.HMSCache;
import com.streamsets.pipeline.stage.lib.hive.cache.HMSCacheSupport;
import com.streamsets.pipeline.stage.lib.hive.cache.HMSCacheType;
import com.streamsets.pipeline.stage.lib.hive.cache.TypeInfoCacheSupport;
import com.streamsets.pipeline.stage.lib.hive.exceptions.HiveStageCheckedException;
import com.streamsets.pipeline.stage.lib.hive.typesupport.HiveType;
import com.streamsets.pipeline.stage.lib.hive.typesupport.HiveTypeInfo;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import com.streamsets.pipeline.stage.processor.hive.HiveMetadataProcessor;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

public final class HiveMetastoreUtil {
  private static final Logger LOG = LoggerFactory.getLogger(HiveMetastoreUtil.class.getCanonicalName());
  private static final String PARTITION_PATH = "/%s=%s";
  private static final String AVRO_SCHEMA_EXT = ".avsc";

  //Common Constants
  public static final String LOCATION_FIELD = "location";
  public static final String TABLE_FIELD = "table";
  public static final String PARTITION_FIELD = "partitions";
  public static final String PARTITION_NAME = "name";
  public static final String COLUMN_NAME = "name";

  //Schema Change Constants
  public static final String COLUMNS_FIELD = "columns";
  public static final String INTERNAL_FIELD = "internal";

  //Partition Rolling Constants
  public static final String PARTITION_VALUE = "value";

  public static final String AVRO_SCHEMA = "avro_schema";

  // Configuration constants
  public static final String CONF = "conf";
  public static final String HIVE_CONFIG_BEAN = "hiveConfigBean";
  public static final String CONF_DIR = "confDir";
  private static final Joiner JOINER = Joiner.on(".");

  public static final String VERSION = "version";
  public static final String METADATA_RECORD_TYPE = "type";
  public static final String SCHEMA_CHANGE_METADATA_RECORD_VERSION = "1";
  public static final String PARTITION_ADDITION_METADATA_RECORD_VERSION = "1";

  public static final String HIVE_OBJECT_ESCAPE =  "`";
  public static final String COLUMN_TYPE = HIVE_OBJECT_ESCAPE + "%s" + HIVE_OBJECT_ESCAPE + " %s";
  public static final String DATABASE_FIELD = "database";
  public static final String SEP = "/";
  public static final String EQUALS = "=";
  public static final String DEFAULT_DBNAME = "default";
  public static final String OPEN_BRACKET = "(";
  public static final String CLOSE_BRACKET = ")";
  public static final String COMMA = ",";
  public static final String SPACE = " ";
  public static final String SINGLE_QUOTE = "'";

  public static final String TYPE_INFO = "typeInfo";
  public static final String TYPE = "type";
  public static final String EXTRA_INFO = "extraInfo";
  public static final String AVRO_SCHEMA_FILE_FORMAT =  AVRO_SCHEMA +"_%s_%s_%s"+AVRO_SCHEMA_EXT;
  public static final String AVRO_SERDE = "org.apache.hadoop.hive.serde2.avro.AvroSerDe";


  private static final SimpleDateFormat datetimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

  private static final String UNSUPPORTED_PARTITION_VALUE_REGEX = "(.*)[\\\\\"\'/?*%?^=\\[\\]]+(.*)";
  private static final Pattern PATTERN_MATCHER = Pattern.compile(UNSUPPORTED_PARTITION_VALUE_REGEX);
  private static final Pattern COLUMN_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final String HDFS_LOCATION_REGEX = "((hdfs://[^/]+(:[0-9]+)?)|(maprfs:))/";

  public enum MetadataRecordType {
    /**
     * Created when completely new table is detected or when
     * existing table have changed schema (new column, ...).
     */
    TABLE,

    /**
     * Created when a new partition is detected.
     */
    PARTITION,
  }

  private HiveMetastoreUtil() {}

  public static void validateConfigFile(String fileName, String hiveConfDirString,
                                        File hiveConfDir, List<Stage.ConfigIssue> issues,
                                        Configuration conf,
                                        Stage.Context context){
    File confFile = new File(hiveConfDir.getAbsolutePath(), fileName);
    if (!confFile.exists()) {
      issues.add(context.createConfigIssue(
          Groups.HIVE.name(),
          JOINER.join(CONF, HIVE_CONFIG_BEAN, CONF_DIR),
          Errors.HIVE_06,
          confFile.getName(),
          hiveConfDirString)
      );
    } else {
      conf.addResource(new Path(confFile.getAbsolutePath()));
    }
  }

  public static Date getTimeBasis(
      Stage.Context context,
      Record record,
      String timeDriver,
      ELEval timeDriverElEval
  ) throws ELEvalException {
    ELVars elVars = context.createELVars();
    TimeNowEL.setTimeNowInContext(elVars, new Date());
    RecordEL.setRecordInContext(elVars, record);
    context.parseEL(timeDriver);
    return timeDriverElEval.eval(elVars, timeDriver, Date.class);
  }

  // Resolve expression from record
  public static String resolveEL(ELEval elEval, ELVars variables, String val) throws ELEvalException {
    return elEval.eval(variables, val, String.class);
  }

  /*
   * Extract information from the list fields of form:
   *
   * Column Type Information : [{name:"column1", typeInfo:{"type": "string", "extraInfo": ""}, {name:"column2", typeInfo:{"type": "int", "extraInfo": ""}]
   * Partition Type Information: [{name:"partition_column1", typeInfo:{"type": "date", "extraInfo": ""}, {name:"partition_column2", typeInfo:{"type": "string", "extraInfo": ""}]
   * Partition Value Information : [{name:"column1", value:"07-05-2016"}, {name:"column2", value:"production"}]
   *
   * If any other List field path is given which does not conform  to above
   * form a stage exception with the mentioned error is throws.
   *
   * Returns a linked hash map so as to maintain the order.
   */
  @SuppressWarnings("unchecked")
  private static <T> void extractInnerMapFromTheList(
      Record metadataRecord,
      String listFieldName,
      String innerPairFirstFieldName,
      String innerPairSecondFieldName,
      boolean isSecondFieldHiveType,
      LinkedHashMap<String, T> returnValMap,
      HiveStageCheckedException exception
  ) throws HiveStageCheckedException{
    boolean throwException = false;
    try {
      if (metadataRecord.has(SEP + listFieldName)) {
        Field columnField = metadataRecord.get(SEP + listFieldName);
        List<Field> columnList = columnField.getValueAsList();
        if (columnList != null) {
          for (Field listElementField : columnList) {
            if (listElementField.getType() != Field.Type.MAP && listElementField.getType() != Field.Type.LIST_MAP) {
              throwException = true;
              break;
            }
            LinkedHashMap<String, Field> innerPair = listElementField.getValueAsListMap();
            String innerPairFirstField = innerPair.get(innerPairFirstFieldName).getValueAsString();
            T retVal;
            if (isSecondFieldHiveType) {
              Field hiveTypeInfoField = innerPair.get(innerPairSecondFieldName);
              HiveType hiveType = HiveType.getHiveTypeFromString(
                  hiveTypeInfoField.getValueAsMap().get(HiveMetastoreUtil.TYPE).getValueAsString()
              );
              retVal = (T) (hiveType.getSupport().generateHiveTypeInfoFromMetadataField(hiveTypeInfoField));
            } else {
              retVal = (T) innerPair.get(innerPairSecondFieldName).getValueAsString();
            }
            returnValMap.put(innerPairFirstField, retVal);
          }
        }
      } else {
        // we allow partition to be empty for non-partitioned table
        if (!listFieldName.equals(PARTITION_FIELD))
          throwException = true;
      }
    } catch(Exception e) {
      LOG.error("Can't parse metadata record", e);
      throwException = true;
      exception.initCause(e);
    }
    if (throwException) {
      throw exception;
    }
  }

  /**
   * Opposite operation of extractInnerMapFromTheList.
   * It takes LinkedHashMap and generate a Field that contains the list.
   * This is to send metadata record to HMS target.
   * This function is called to for partition type list and partition value list.
   */
  private static <T> Field generateInnerFieldFromTheList(
      LinkedHashMap<String, T> original,
      String innerPairFirstFieldName,
      String innerPairSecondFieldName,
      boolean isSecondFieldHiveType
  ) throws HiveStageCheckedException {
    List<Field> columnList = new LinkedList<>();
    for(Map.Entry<String,T> pair:  original.entrySet()) {
      Map<String, Field> entry = new LinkedHashMap<>();
      entry.put(innerPairFirstFieldName, Field.create(pair.getKey()));
      if (isSecondFieldHiveType){
        HiveTypeInfo hiveTypeInfo = (HiveTypeInfo) pair.getValue();
        entry.put(
            innerPairSecondFieldName,
            hiveTypeInfo.getHiveType().getSupport().generateHiveTypeInfoFieldForMetadataRecord(hiveTypeInfo)
        );
      } else {
        entry.put(innerPairSecondFieldName, Field.create(pair.getValue().toString())); //stored value is "INT". need to fix this
      }
      columnList.add(Field.create(entry));
    }
    return !columnList.isEmpty() ? Field.create(columnList) : null;
  }

  /**
   * Get qualified table name (defined as dbName.tableName)
   * @param dbName Database name
   * @param tableName Table Name
   * @return the qualified table name.
   */
  public static String getQualifiedTableName(String dbName, String tableName) {
    return (dbName == null || dbName.isEmpty())?
        escapeHiveObjectName(tableName) :
        JOINER.join(escapeHiveObjectName(dbName), escapeHiveObjectName(tableName))
        ;
  }

  /**
   * Escape given Hive object name (Table, databse, column) to be safe to used inside SQL queries.
   */
  public static String escapeHiveObjectName(String name) {
    return HIVE_OBJECT_ESCAPE + name + HIVE_OBJECT_ESCAPE;
  }

  /**
   * Get a full path from warehouse directory to table's root directory
   * The path structure is /<warehouse directory>/<db name>.db/<table name>
   * @param warehouseDir Directory to HMS warehouse directory
   * @param dbName Database name
   * @param tableName Table name
   * @return String that contains full path of target directory
   */
  public static String getTargetDirectory(String warehouseDir, String dbName, String tableName) {
    Utils.checkNotNull(warehouseDir, "warehouseDir");
    Utils.checkNotNull(dbName, "dbName");
    Utils.checkNotNull(tableName, "tableName");
    if (dbName.equals(HiveMetadataProcessor.DEFAULT_DB)) {
      return String.format("%s/%s", warehouseDir, tableName);
    } else
      return String.format("%s/%s.db/%s", warehouseDir, dbName, tableName);
  }

  /**
   * Extract column information from the column list in "/columns" field.<br>
   *
   * Column Type Information should exist in this form: <br>
   *   [{name:"column1", typeInfo:{"type": "string", "extraInfo": ""},
   *   {name:"column2", typeInfo:{"type": "int", "extraInfo": ""}]
   *
   * @param metadataRecord record which contains the {@link #COLUMNS_FIELD} and conform to the above structure.
   * @return Map of column name to column type
   * @throws HiveStageCheckedException if no column information exists or the record has invalid fields.
   */
  public static LinkedHashMap<String, HiveTypeInfo> getColumnNameType(Record metadataRecord) throws HiveStageCheckedException {
    LinkedHashMap<String, HiveTypeInfo> columnNameType = new LinkedHashMap<>();
    extractInnerMapFromTheList(
        metadataRecord,
        COLUMNS_FIELD,
        COLUMN_NAME,
        TYPE_INFO,
        true,
        columnNameType,
        new HiveStageCheckedException(Errors.HIVE_17, COLUMNS_FIELD, metadataRecord)
    );
    return columnNameType;
  }

  /**
   * Extract column information from the Partition list in "/partitions" field.<br>
   *
   * Partition Information should exist in this form:<br>
   *   [{name:"partition_column1", typeInfo:{"type": "date", "extraInfo": ""},
   *   {name:"partition_column2", typeInfo:{"type": "string", "extraInfo": ""}]
   *
   * @param metadataRecord record which contains the {@link #PARTITION_FIELD} and conform to the above structure.
   * @return Map of partition name to partition type
   * @throws HiveStageCheckedException if no partition information exists or the record has invalid fields.
   */
  public static LinkedHashMap<String, HiveTypeInfo> getPartitionNameType(Record metadataRecord)
      throws HiveStageCheckedException{
    LinkedHashMap<String, HiveTypeInfo> partitionNameType = new LinkedHashMap<>();
    extractInnerMapFromTheList(
        metadataRecord,
        PARTITION_FIELD,
        PARTITION_NAME,
        TYPE_INFO,
        true,
        partitionNameType,
        new HiveStageCheckedException(Errors.HIVE_17, PARTITION_FIELD, metadataRecord)
    );
    return partitionNameType;
  }

  /**
   * Extract column information from the Partition list in "/partitions" field.<br>
   *
   * Partition Value Information should exist in this form: <br>
   *   [{name:"column1", value:"07-05-2016"}, {name:"column2", value:"production"}]
   *
   * @param metadataRecord record which contains the {@link #PARTITION_FIELD} and conform to the above structure.
   * @return Map of partition name to partition value
   * @throws HiveStageCheckedException if no partition value information exists or the record has invalid fields.
   */
  public static LinkedHashMap<String, String> getPartitionNameValue(Record metadataRecord) throws HiveStageCheckedException{
    LinkedHashMap<String, String> partitionNameValue = new LinkedHashMap<>();
    extractInnerMapFromTheList(
        metadataRecord,
        PARTITION_FIELD,
        PARTITION_NAME,
        PARTITION_VALUE,
        false,
        partitionNameValue,
        new HiveStageCheckedException(Errors.HIVE_17, PARTITION_FIELD, metadataRecord)
    );
    return partitionNameValue;
  }

  /**
   * Returns true if this is a TABLE metadata request (new or changed table).
   *
   * @param metadataRecord the metadata record
   * @return boolean true or false indicating whether this metadata record is schema change / partition roll record.
   */
  public static boolean isSchemaChangeRecord(Record metadataRecord) {
    return MetadataRecordType.TABLE.name().equals(metadataRecord.get(SEP + METADATA_RECORD_TYPE).getValueAsString());
  }

  public static void validateMetadataRecordForRecordTypeAndVersion(Record metadataRecord) throws HiveStageCheckedException {
    if (!metadataRecord.has(SEP + METADATA_RECORD_TYPE)) {
      throw new HiveStageCheckedException(Errors.HIVE_17, METADATA_RECORD_TYPE, metadataRecord);
    }
    if(!metadataRecord.has(SEP + VERSION)) {
      throw new HiveStageCheckedException(Errors.HIVE_17, VERSION, metadataRecord);
    }
  }

  /**
   * Get Table Name from the metadata record.
   * @param metadataRecord the metadata record
   * @return Table Name
   * @throws HiveStageCheckedException if the table field does not exist in the metadata record.
   */
  public static String getTableName(Record metadataRecord) throws HiveStageCheckedException {
    if (metadataRecord.has(SEP + TABLE_FIELD)) {
      return metadataRecord.get(SEP + TABLE_FIELD).getValueAsString();
    }
    throw new HiveStageCheckedException(Errors.HIVE_17, TABLE_FIELD, metadataRecord);
  }

  /**
   * Get Database Name from the metadata record.
   * @param metadataRecord the metadata record
   * @return Database Name
   * @throws HiveStageCheckedException if the database name does not exist in the metadata record.
   */
  public static String getDatabaseName(Record metadataRecord) throws HiveStageCheckedException {
    if (metadataRecord.has(SEP + DATABASE_FIELD)) {
      String dbName = metadataRecord.get(SEP + DATABASE_FIELD).getValueAsString();
      return dbName.isEmpty()? DEFAULT_DBNAME : dbName;
    }
    throw new HiveStageCheckedException(Errors.HIVE_17, DATABASE_FIELD, metadataRecord);
  }

  /**
   * Get internal field from the metadata record.
   * @param metadataRecord the metadata record
   * @return internal field value
   * @throws HiveStageCheckedException if the internal field does not exist in the metadata record.
   */
  public static boolean getInternalField(Record metadataRecord) throws HiveStageCheckedException{
    if (metadataRecord.has(SEP + INTERNAL_FIELD)) {
      return metadataRecord.get(SEP + INTERNAL_FIELD).getValueAsBoolean();
    }
    throw new HiveStageCheckedException(Errors.HIVE_17, INTERNAL_FIELD, metadataRecord);
  }

  /**
   * Get Location from the metadata record.
   * @param metadataRecord the metadata record
   * @return location
   * @throws HiveStageCheckedException if the location field does not exist in the metadata record.
   */
  public static String getLocation(Record metadataRecord) throws HiveStageCheckedException{
    if (metadataRecord.has(SEP + LOCATION_FIELD)) {
      return metadataRecord.get(SEP + LOCATION_FIELD).getValueAsString();
    }
    throw new HiveStageCheckedException(Errors.HIVE_17, LOCATION_FIELD, metadataRecord);
  }

  /**
   * Get Avro Schema from Metadata Record.
   * @param metadataRecord the metadata record.
   * @return Avro Schema
   * @throws HiveStageCheckedException if the avro schema field does not exist in the metadata record.
   */
  public static String getAvroSchema(Record metadataRecord) throws HiveStageCheckedException{
    if (metadataRecord.has(SEP + AVRO_SCHEMA)) {
      return metadataRecord.get(SEP + AVRO_SCHEMA).getValueAsString();
    }
    throw new HiveStageCheckedException(Errors.HIVE_17, AVRO_SCHEMA, metadataRecord);
  }

  /**
   * Fill in metadata to Record. This is for new partition creation.
   */
  public static Field newPartitionMetadataFieldBuilder(
      String database,
      String tableName,
      LinkedHashMap<String, String> partitionList,
      String location) throws HiveStageCheckedException {
    LinkedHashMap<String, Field> metadata = new LinkedHashMap<>();
    metadata.put(VERSION, Field.create(PARTITION_ADDITION_METADATA_RECORD_VERSION));
    metadata.put(METADATA_RECORD_TYPE, Field.create(MetadataRecordType.PARTITION.name()));
    metadata.put(DATABASE_FIELD, Field.create(database));
    metadata.put(TABLE_FIELD, Field.create(tableName));
    metadata.put(LOCATION_FIELD, Field.create(location));

    //fill in the partition list here
    metadata.put(
        PARTITION_FIELD,
        generateInnerFieldFromTheList(
            partitionList,
            PARTITION_NAME,
            PARTITION_VALUE,
            false
        )
    );
    return Field.create(metadata);
  }

  /**
   * Fill in metadata to Record. This is for new schema creation.
   */
  public static Field newSchemaMetadataFieldBuilder  (
      String database,
      String tableName,
      LinkedHashMap<String, HiveTypeInfo> columnList,
      LinkedHashMap<String, HiveTypeInfo> partitionTypeList,
      boolean internal,
      String location,
      String avroSchema
  ) throws HiveStageCheckedException  {
    LinkedHashMap<String, Field> metadata = new LinkedHashMap<>();
    metadata.put(VERSION, Field.create(SCHEMA_CHANGE_METADATA_RECORD_VERSION));
    metadata.put(METADATA_RECORD_TYPE, Field.create(MetadataRecordType.TABLE.name()));
    metadata.put(DATABASE_FIELD, Field.create(database));
    metadata.put(TABLE_FIELD, Field.create(tableName));
    metadata.put(LOCATION_FIELD, Field.create(location));

    //fill in column type list here
    metadata.put(
        COLUMNS_FIELD,
        generateInnerFieldFromTheList(
            columnList,
            COLUMN_NAME,
            TYPE_INFO,
            true
        )
    );
    //fill in partition type list here
    if (partitionTypeList != null && !partitionTypeList.isEmpty()) {
      metadata.put(
          PARTITION_FIELD,
          generateInnerFieldFromTheList(
              partitionTypeList,
              PARTITION_NAME,
              TYPE_INFO,
              true
          )
      );
    }
    metadata.put(INTERNAL_FIELD, Field.create(internal));
    metadata.put(AVRO_SCHEMA, Field.create(avroSchema));
    return Field.create(metadata);
  }

  /**
   *  Evaluate precision or scale in context of record and given field path.
   */
  public static int resolveScaleOrPrecisionExpression(
      String type,
      ELEval elEval,
      ELVars variables,
      String defaultScaleEL,
      String fieldPath
  ) throws ELEvalException, HiveStageCheckedException {
    // By default we take the constant given to this method
    String value = defaultScaleEL;
    // And if so evaluate it
    if (elEval != null) {
      FieldPathEL.setFieldInContext(variables, fieldPath);
      value = elEval.eval(
          variables,
          defaultScaleEL,
          String.class
      );
    }

    // Finally try to parse output as an integer. Failure means that we are unable to calculate proper scale/precision.
    try {
      return Integer.parseInt(value);
    } catch(NumberFormatException e) {
      throw new HiveStageCheckedException(Errors.HIVE_29, type, fieldPath, defaultScaleEL, value, e);
    }
  }

  private static void validateScaleAndPrecision(String fieldName, Field field, int precision, int scale) throws HiveStageCheckedException{
    // Validate calculated precision/scale
    if (precision > 38) {
      throw new HiveStageCheckedException(com.streamsets.pipeline.stage.processor.hive.Errors.HIVE_METADATA_07, precision, "precision", fieldName, 1);
    }
    if (scale > 38) {
      throw new HiveStageCheckedException(com.streamsets.pipeline.stage.processor.hive.Errors.HIVE_METADATA_07, scale, "scale", fieldName, 0);
    }
    if (precision < 1) {
      throw new HiveStageCheckedException(com.streamsets.pipeline.stage.processor.hive.Errors.HIVE_METADATA_07, precision, "precision", fieldName, 1);
    }
    if (scale < 0) {
      throw new HiveStageCheckedException(com.streamsets.pipeline.stage.processor.hive.Errors.HIVE_METADATA_07, precision, "precision", fieldName, 0);
    }
    if (scale > precision) {
      throw new HiveStageCheckedException(com.streamsets.pipeline.stage.processor.hive.Errors.HIVE_METADATA_08, scale, fieldName, precision);
    }

    // Validate that given decimal value is in the range of what was calculated
    BigDecimal value = field.getValueAsDecimal();
    if(value != null) {
      if (value.scale() > scale) {
        throw new HiveStageCheckedException(Errors.HIVE_26, value, fieldName, "scale", value.scale(), scale);
      }
      if (value.precision() > precision) {
        throw new HiveStageCheckedException(Errors.HIVE_26, value, fieldName, "precision", value.precision(), precision);
      }
    }
  }


  /**
   * Convert a Record to LinkedHashMap. This is for comparing the structure of incoming Record with cache.
   * Since Avro does not support char, short, and date types, it needs to convert the type to corresponding
   * supported types and change the value in record.
   * @param record incoming Record
   * @return LinkedHashMap version of record. Key is the column name, and value is column type in HiveType
   * @throws HiveStageCheckedException
   */
  public static LinkedHashMap<String, HiveTypeInfo> convertRecordToHMSType(
      Record record,
      ELEval scaleEL,
      ELEval precisionEL,
      String scaleExpression,
      String precisionExpression,
      ELVars variables
  ) throws HiveStageCheckedException, ELEvalException {
    LinkedHashMap<String, HiveTypeInfo> columns = new LinkedHashMap<>();
    LinkedHashMap<String, Field> list = record.get().getValueAsListMap();
    for(Map.Entry<String,Field> pair:  list.entrySet()) {
      if (pair.getKey().isEmpty()) {
        throw new HiveStageCheckedException(Errors.HIVE_01, "Field names are empty");
      }
      Field currField = pair.getValue();
      switch(currField.getType()) {
        case SHORT:
          currField = Field.create(Field.Type.INTEGER, currField.getValue());
          break;
        case CHAR:
          currField = Field.create(currField.getValueAsString());
          break;
        case DATETIME:
          currField = Field.create(Field.Type.STRING, currField.getValue() == null ? null : datetimeFormat.format(currField.getValueAsDate()));
          break;
        case TIME:
          currField = Field.create(Field.Type.STRING, currField.getValue() == null ? null : timeFormat.format(currField.getValueAsTime()));
          break;
      }

      // Update the Field type and value in Record
      pair.setValue(currField);
      HiveType hiveType = HiveType.getHiveTypeforFieldType(currField.getType());
      HiveTypeInfo hiveTypeInfo;
      // Some types requires special checks or alterations
      if (hiveType == HiveType.DECIMAL) {
        int precision = resolveScaleOrPrecisionExpression("precision", precisionEL, variables, precisionExpression, pair.getKey());
        int scale = resolveScaleOrPrecisionExpression("scale", scaleEL, variables, scaleExpression, pair.getKey());
        validateScaleAndPrecision(pair.getKey(), currField, precision, scale);
        hiveTypeInfo = hiveType.getSupport().generateHiveTypeInfoFromRecordField(currField, precision, scale);
        // We need to make sure that all java objects have the same scale
        if(currField.getValue() != null) {
          pair.setValue(Field.create(currField.getValueAsDecimal().setScale(scale)));
        }
      } else {
        hiveTypeInfo = hiveType.getSupport().generateHiveTypeInfoFromRecordField(currField);
      }
      columns.put(pair.getKey().toLowerCase(), hiveTypeInfo);
    }
    return columns;
  }

  public static boolean validateName(String valName){
    return MetaStoreUtils.validateName(valName);
  }

  /**
   * Checks if partition value contains unsupported character.
   * @param value String to check
   * @return True if the string contains unsuppoted character.
   */
  public static boolean hasUnsupportedChar(String value) {
    return PATTERN_MATCHER.matcher(value).matches();
  }

  /**
   * Validate that given column name is valid.
   */
  public static boolean validateColumnName(String colName) {
    return COLUMN_NAME_PATTERN.matcher(colName).matches();
  }

  /**
   *  Generate avro schema from column name and type information. typeInfo in 1st parameter
   *  needs to contain precision and scale information in the value(HiveTypeInfo).
   *  The 2nd parameter qualifiedName will be the name of Avro Schema.
   * @param typeInfo  Record structure
   * @param qualifiedName qualified name that will be the name of generated avro schema
   * @return String avro schema
   * @throws StageException
   */
  public static String generateAvroSchema(Map<String, HiveTypeInfo> typeInfo, String qualifiedName)
      throws HiveStageCheckedException {
    Utils.checkNotNull(typeInfo, "Error TypeInfo cannot be null");
    // Avro doesn't allow "`" in names, so we're dropping those from qualified name
    AvroHiveSchemaGenerator gen = new AvroHiveSchemaGenerator(qualifiedName.replace("`", ""));
    try {
      return gen.inferSchema(typeInfo);
    } catch (StageException e) {
      //So that any error to generate avro schema will result in onRecordErrorException and routed to error lane.
      throw new HiveStageCheckedException(e.getErrorCode(), e.getParams());
    }
  }

  /**
   * Checks whether the number of partition columns and names match w.r.t hive.
   * @param typeInfo {@link com.streamsets.pipeline.stage.lib.hive.cache.TypeInfoCacheSupport.TypeInfo}
   * @param partitionValMap Map of partition name to values
   * @param qualifiedTableName Qualified table name.
   * @throws HiveStageCheckedException if there is a mismatch w.r.t hive
   */
  public static void validatePartitionInformation(
      TypeInfoCacheSupport.TypeInfo typeInfo,
      LinkedHashMap<String, String> partitionValMap,
      String qualifiedTableName
  ) throws HiveStageCheckedException {
    Set<String> partitionNamesInHive = typeInfo.getPartitionTypeInfo().keySet();
    Set<String> partitionNames = partitionValMap.keySet();
    if (!(partitionNamesInHive.size() == partitionNames.size()
        && partitionNamesInHive.containsAll(partitionNames))) {
      LOG.error(Utils.format(
          "Partition mismatch. In Hive: {}, In Record : {}",
          partitionNamesInHive.size(),
          partitionNames.size())
      );
      throw new HiveStageCheckedException(Errors.HIVE_27, qualifiedTableName);
    }
  }

  /**
   * Build a partition path for the external table.
   * @param partitions A list of key-value pair to build a partition path
   * @return String that represents partition path
   */
  public static String generatePartitionPath(LinkedHashMap<String, String> partitions) {
    StringBuilder builder = new StringBuilder();
    for(Map.Entry<String, String> pair:  partitions.entrySet()) {
      builder.append(String.format(PARTITION_PATH, pair.getKey(), pair.getValue()));
    }
    return builder.toString();
  }

  /**
   * Returns the hdfs paths where the avro schema is stored after serializing.
   * Path is appended with current time so as to have an ordering.
   * @param schemaFolder Schema Folder (If this starts with '/' it is considered absolute)
   * @return Hdfs Path String.
   */
  public static String serializeSchemaToHDFS(
      UserGroupInformation loginUGI,
      final FileSystem fs,
      final String location,
      final String schemaFolder,
      final String databaseName,
      final String tableName,
      final String schemaJson
  ) throws StageException {
    String folderLocation;
    if (schemaFolder.startsWith(SEP)) {
      folderLocation = schemaFolder;
    } else {
      folderLocation = location + SEP + schemaFolder;
    }
    final Path schemasFolderPath = new Path(folderLocation);
    final String path =  folderLocation + SEP + String.format(
        AVRO_SCHEMA_FILE_FORMAT,
        databaseName,
        tableName,
        DateFormatUtils.format(new Date(System.currentTimeMillis()), "yyyy-MM-dd--HH-mm-ss")
    );
    try {
      loginUGI.doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception{
          if (!fs.exists(schemasFolderPath)) {
            fs.mkdirs(schemasFolderPath);
          }
          Path schemaFilePath = new Path(path);
          //This will never happen unless two HMS targets are writing, we will error out for this
          //and let user handle this via error record handling.
          if (!fs.exists(schemaFilePath)) {
            try (FSDataOutputStream os = fs.create(schemaFilePath)) {
              byte []schemaBytes = schemaJson.getBytes("UTF-8");
              os.write(schemaBytes, 0, schemaBytes.length);
            }
          } else {
            LOG.error(Utils.format("Already schema file {} exists in HDFS", path));
            throw new IOException("Already schema file exists");
          }
          return null;
        }
      });
    } catch (Exception e) {
      LOG.error("Error in Writing Schema to HDFS: " + e.toString(), e);
      throw new StageException(Errors.HIVE_18, path, e.getMessage());
    }
    return path;
  }

  public static Connection getHiveConnection(
      final String jdbcUrl,
      final UserGroupInformation loginUgi
  ) throws StageException {
    try {
      return loginUgi.doAs(new PrivilegedExceptionAction<Connection>() {
        @Override
        public Connection run() throws SQLException {
          return DriverManager.getConnection(jdbcUrl);
        }
      });
    } catch (Exception e) {
      LOG.error("Failed to connect to Hive with JDBC URL:" + jdbcUrl, e);
      throw new StageException(Errors.HIVE_22, jdbcUrl, e.getMessage());
    }
  }

  public static String stripHdfsHostAndPort(String location) {
    Utils.checkNotNull(location, "HDFS Partition location");
    Utils.checkArgument(
        !location.isEmpty(), "HDFS location cannot be empty"
    );
    Utils.checkArgument(
        location.matches(HDFS_LOCATION_REGEX + ".*"),
        "HDFS Partition location should match pattern" + HDFS_LOCATION_REGEX + ".*"
    );
    return location.replaceFirst(HDFS_LOCATION_REGEX, HiveMetastoreUtil.SEP);
  }

  /**
   * Gets cached {@link com.streamsets.pipeline.stage.lib.hive.cache.HMSCacheSupport.HMSCacheInfo}
   * from cache.<br>
   * First call getIfPresent to obtain data from local cache.If not exists, load from HMS
   *
   * @param <T> {@link HMSCacheSupport.HMSCacheInfo}
   * @param hmsCache {@link HMSCache}
   * @param cacheType Type of cache to load.
   * @param qualifiedName qualified table name.
   * @return Cache object if successfully loaded. Null if no data is found in cache.
   * @throws StageException
   */
  @SuppressWarnings("unchecked")
  public static <T extends HMSCacheSupport.HMSCacheInfo> T getCacheInfo(
      HMSCache hmsCache,
      HMSCacheType cacheType,
      String qualifiedName
  ) throws StageException {
    HMSCacheSupport.HMSCacheInfo cacheInfo = hmsCache.getIfPresent(cacheType, qualifiedName);
    if (cacheType != HMSCacheType.AVRO_SCHEMA_INFO && cacheInfo == null) {
      // Try loading by executing HMS query
      cacheInfo = hmsCache.getOrLoad(cacheType, qualifiedName);
    }
    return (T)cacheInfo;
  }
}
