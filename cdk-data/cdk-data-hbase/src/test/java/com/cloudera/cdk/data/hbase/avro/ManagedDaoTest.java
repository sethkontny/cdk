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
package com.cloudera.cdk.data.hbase.avro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.util.Utf8;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloudera.cdk.data.PartitionKey;
import com.cloudera.cdk.data.PartitionStrategy;
import com.cloudera.cdk.data.dao.Dao;
import com.cloudera.cdk.data.dao.EntityScanner;
import com.cloudera.cdk.data.dao.IncompatibleSchemaException;
import com.cloudera.cdk.data.dao.SchemaManager;
import com.cloudera.cdk.data.dao.SchemaNotFoundException;
import com.cloudera.cdk.data.hbase.avro.entities.ArrayRecord;
import com.cloudera.cdk.data.hbase.avro.entities.EmbeddedRecord;
import com.cloudera.cdk.data.hbase.avro.entities.TestEnum;
import com.cloudera.cdk.data.hbase.avro.entities.TestRecord;
import com.cloudera.cdk.data.hbase.avro.impl.AvroKeyEntitySchemaParser;
import com.cloudera.cdk.data.hbase.avro.impl.AvroUtils;
import com.cloudera.cdk.data.hbase.manager.DefaultSchemaManager;
import com.cloudera.cdk.data.hbase.manager.generated.ManagedSchema;
import com.cloudera.cdk.data.hbase.testing.HBaseTestUtils;
import com.cloudera.cdk.data.hbase.tool.SchemaTool;

public class ManagedDaoTest {

  private static final String testRecord;
  private static final String testRecordv2;
  private static final String badMigrationRecordAddKeyField;
  private static final String badMigrationRecordAddFieldNoDefault;
  private static final String badMigrationRecordAddSubFieldNoDefault;
  private static final String badMigrationRecordModifiedMapping;
  private static final String badMigrationRecordIntToLong;
  private static final String goodMigrationRecordAddField;
  private static final String goodMigrationRecordAddSubField;
  private static final String goodMigrationRecordRemoveField;
  private static final String managedRecordString;
  private static final String tableName = "testtable";
  private static final String managedTableName = "managed_schemas";

  private HTablePool tablePool;

  static {
    try {
      testRecord = AvroUtils.inputStreamToString(AvroDaoTest.class
          .getResourceAsStream("/TestRecord.avsc"));
      testRecordv2 = AvroUtils.inputStreamToString(AvroDaoTest.class
          .getResourceAsStream("/TestRecordv2.avsc"));
      badMigrationRecordAddKeyField = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/BadMigrationRecordAddKeyField.avsc"));      
      badMigrationRecordAddFieldNoDefault = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/BadMigrationRecordAddFieldNoDefault.avsc"));
      badMigrationRecordAddSubFieldNoDefault = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/BadMigrationRecordAddSubFieldNoDefault.avsc"));
      badMigrationRecordModifiedMapping = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/BadMigrationRecordModifiedMapping.avsc"));
      badMigrationRecordIntToLong = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/BadMigrationRecordIntToLong.avsc"));
      goodMigrationRecordAddField = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/GoodMigrationRecordAddField.avsc"));
      goodMigrationRecordAddSubField = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/GoodMigrationRecordAddSubField.avsc"));
      goodMigrationRecordRemoveField = AvroUtils
          .inputStreamToString(AvroDaoTest.class
              .getResourceAsStream("/GoodMigrationRecordRemoveField.avsc"));
      managedRecordString = AvroUtils.inputStreamToString(AvroDaoTest.class
          .getResourceAsStream("/ManagedSchema.avsc"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    HBaseTestUtils.getMiniCluster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    HBaseTestUtils.util.deleteTable(Bytes.toBytes(tableName));
  }

  @Before
  public void before() throws Exception {
    tablePool = new HTablePool(HBaseTestUtils.getConf(), 10);
    SchemaTool tool = new SchemaTool(new HBaseAdmin(HBaseTestUtils.getConf()),
        new DefaultSchemaManager(tablePool));
    tool.createOrMigrateSchema(tableName, testRecord, true);
    tool.createOrMigrateSchema(tableName, testRecordv2, true);
  }

  @After
  public void after() throws Exception {
    tablePool.close();
    HBaseTestUtils.util.truncateTable(Bytes.toBytes(tableName));
    HBaseTestUtils.util.truncateTable(Bytes.toBytes(managedTableName));
  }

  private PartitionKey createKey(PartitionStrategy partitionStrategy,
      long uniqueIdx) {
    return partitionStrategy.partitionKey("part1_" + uniqueIdx, "part2_"
        + uniqueIdx);
  }

  private GenericRecord createGenericEntity(long uniqueIdx) {
    return createGenericEntity(uniqueIdx, testRecord);
  }

  /**
   * Creates a generic entity with the given schema, which must be migratable
   * from TestRecord. Data from TestRecord is filled in.
   * 
   * @param uniqueIdx
   * @param schemaString
   * @return
   */
  private GenericRecord createGenericEntity(long uniqueIdx, String schemaString) {
    String iStr = Long.toString(uniqueIdx);

    Schema.Parser parser = new Schema.Parser();
    Schema entitySchema = parser.parse(schemaString);
    Schema embeddedSchema = entitySchema.getField("field4").schema();
    Schema arrayValueSchema = entitySchema.getField("field5").schema()
        .getElementType();

    GenericRecord entity = new GenericData.Record(entitySchema);
    entity.put("keyPart1", "part1_" + iStr);
    entity.put("keyPart2", "part2_" + iStr);
    entity.put("field1", "field1_" + iStr);
    entity.put("field2", "field2_" + iStr);
    entity.put("enum", "ENUM3");
    Map<CharSequence, CharSequence> field3Map = new HashMap<CharSequence, CharSequence>();
    field3Map.put("field3_key_1_" + iStr, "field3_value_1_" + iStr);
    field3Map.put("field3_key_2_" + iStr, "field3_value_2_" + iStr);
    entity.put("field3", field3Map);
    GenericRecord embedded = new GenericData.Record(embeddedSchema);
    embedded.put("embeddedField1", "embedded1");
    embedded.put("embeddedField2", 2L);
    entity.put("field4", embedded);

    List<GenericRecord> arrayRecordList = new ArrayList<GenericRecord>();
    GenericRecord subRecord = new GenericData.Record(arrayValueSchema);
    subRecord.put("subfield1", "subfield1");
    subRecord.put("subfield2", 1L);
    subRecord.put("subfield3", "subfield3");
    arrayRecordList.add(subRecord);

    subRecord = new GenericData.Record(arrayValueSchema);
    subRecord.put("subfield1", "subfield4");
    subRecord.put("subfield2", 1L);
    subRecord.put("subfield3", "subfield6");
    arrayRecordList.add(subRecord);

    entity.put("field5", arrayRecordList);
    return entity;
  }

  private TestRecord createSpecificEntity(long uniqueIdx) {
    String iStr = Long.toString(uniqueIdx);

    Map<String, String> field3Map = new HashMap<String, String>();
    field3Map.put("field3_key_1_" + iStr, "field3_value_1_" + iStr);
    field3Map.put("field3_key_2_" + iStr, "field3_value_2_" + iStr);

    EmbeddedRecord embeddedRecord = EmbeddedRecord.newBuilder()
        .setEmbeddedField1("embedded1").setEmbeddedField2(2L).build();

    List<ArrayRecord> arrayRecordList = new ArrayList<ArrayRecord>(2);
    ArrayRecord subRecord = ArrayRecord.newBuilder().setSubfield1("subfield1")
        .setSubfield2(1L).setSubfield3("subfield3").build();
    arrayRecordList.add(subRecord);
    subRecord = ArrayRecord.newBuilder().setSubfield1("subfield4")
        .setSubfield2(1L).setSubfield3("subfield6").build();
    arrayRecordList.add(subRecord);

    TestRecord entity = TestRecord.newBuilder().setKeyPart1("part1_" + iStr)
        .setKeyPart2("part2_" + iStr).setField1("field1_" + iStr)
        .setField2("field2_" + iStr).setEnum$(TestEnum.ENUM3)
        .setField3(field3Map).setField4(embeddedRecord)
        .setField5(arrayRecordList).setIncrement(10L).build();
    return entity;
  }

  @SuppressWarnings("unchecked")
  private void compareEntitiesWithString(long uniqueIdx, IndexedRecord record) {
    String iStr = Long.toString(uniqueIdx);
    assertEquals("part1_" + iStr, record.get(0));
    assertEquals("part2_" + iStr, record.get(1));
    assertEquals("field1_" + iStr, record.get(2));
    assertEquals("field2_" + iStr, record.get(3));
    assertEquals(TestEnum.ENUM3.toString(), record.get(4).toString());
    assertEquals(
        "field3_value_1_" + iStr,
        ((Map<CharSequence, CharSequence>) record.get(5)).get("field3_key_1_"
            + iStr));
    assertEquals(
        "field3_value_2_" + iStr,
        ((Map<CharSequence, CharSequence>) record.get(5)).get("field3_key_2_"
            + iStr));
    assertEquals("embedded1", ((IndexedRecord) record.get(6)).get(0));
    assertEquals(2L, ((IndexedRecord) record.get(6)).get(1));

    assertEquals(2, ((List<?>) record.get(7)).size());
    assertEquals("subfield1",
        ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(0));
    assertEquals(1L, ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(1));
    assertEquals("subfield3",
        ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(2));
    assertEquals("subfield4",
        ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(0));
    assertEquals(1L, ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(1));
    assertEquals("subfield6",
        ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(2));
  }

  @SuppressWarnings("unchecked")
  private void compareEntitiesWithUtf8(long uniqueIdx, IndexedRecord record) {
    String iStr = Long.toString(uniqueIdx);
    assertEquals(new Utf8("field1_" + iStr), record.get(2));
    assertEquals(new Utf8("field2_" + iStr), record.get(3));
    assertEquals(TestEnum.ENUM3.toString(), record.get(4).toString());
    assertEquals(new Utf8("field3_value_1_" + iStr),
        ((Map<CharSequence, CharSequence>) record.get(5)).get(new Utf8(
            "field3_key_1_" + iStr)));
    assertEquals(new Utf8("field3_value_2_" + iStr),
        ((Map<CharSequence, CharSequence>) record.get(5)).get(new Utf8(
            "field3_key_2_" + iStr)));
    assertEquals(new Utf8("embedded1"), ((IndexedRecord) record.get(6)).get(0));
    assertEquals(2L, ((IndexedRecord) record.get(6)).get(1));

    assertEquals(2, ((List<?>) record.get(7)).size());
    assertEquals(new Utf8("subfield1"),
        ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(0));
    assertEquals(1L, ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(1));
    assertEquals(new Utf8("subfield3"),
        ((IndexedRecord) ((List<?>) record.get(7)).get(0)).get(2));
    assertEquals(new Utf8("subfield4"),
        ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(0));
    assertEquals(1L, ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(1));
    assertEquals(new Utf8("subfield6"),
        ((IndexedRecord) ((List<?>) record.get(7)).get(1)).get(2));
  }

  @Test
  public void testGeneric() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<GenericRecord> dao = new GenericAvroDao(tablePool, tableName,
        "TestRecord", manager, testRecord);

    // Create the new entities
    for (int i = 0; i < 10; ++i) {
      GenericRecord entity = createGenericEntity(i);
      dao.put(entity);
    }

    // ensure the new entities are what we expect with get operations
    for (int i = 0; i < 10; ++i) {
      compareEntitiesWithUtf8(i,
          dao.get(createKey(dao.getPartitionStrategy(), i)));
    }

    // ensure the new entities are what we expect with scan operations
    int cnt = 0;
    EntityScanner<GenericRecord> entityScanner = dao.getScanner();
    try {
      for (GenericRecord entity : entityScanner) {
        compareEntitiesWithUtf8(cnt, entity);
        cnt++;
      }
      assertEquals(10, cnt);
    } finally {
      entityScanner.close();
    }
  }

  @Test
  public void testSpecific() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<TestRecord> dao = new SpecificAvroDao<TestRecord>(tablePool, tableName,
        "TestRecord", manager);

    // Create the new entities
    for (int i = 0; i < 10; ++i) {
      TestRecord entity = createSpecificEntity(i);
      dao.put(entity);
    }

    // ensure the new entities are what we expect with get operations
    for (int i = 0; i < 10; ++i) {
      compareEntitiesWithString(i,
          dao.get(createKey(dao.getPartitionStrategy(), i)));
    }

    // ensure the new entities are what we expect with scan operations
    int cnt = 0;
    EntityScanner<TestRecord> entityScanner = dao.getScanner();
    try {
      for (TestRecord entity : entityScanner) {
        compareEntitiesWithString(cnt, entity);
        cnt++;
      }
      assertEquals(10, cnt);
    } finally {
      entityScanner.close();
    }
  }

  @Test
  public void testMigrateEntities() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<TestRecord> dao = new SpecificAvroDao<TestRecord>(tablePool, tableName,
        "TestRecord", manager);

    manager.migrateSchema(tableName, "TestRecord", goodMigrationRecordAddField);

    Dao<TestRecord> afterDao = new SpecificAvroDao<TestRecord>(tablePool,
        tableName, "TestRecord", manager);

    // Create the new entities
    for (int i = 0; i < 10; ++i) {
      TestRecord entity = createSpecificEntity(i);
      afterDao.put(entity);
    }

    // ensure the new entities are what we expect with get operations
    for (int i = 0; i < 10; ++i) {
      compareEntitiesWithString(i,
          dao.get(createKey(dao.getPartitionStrategy(), i)));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testMigrateAndPut() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<GenericRecord> dao = new GenericAvroDao(tablePool, tableName,
        "TestRecord", manager, testRecord);

    manager.migrateSchema(tableName, "TestRecord", goodMigrationRecordAddField);

    SchemaManager afterManager = new DefaultSchemaManager(tablePool);
    Dao<GenericRecord> afterDao = new GenericAvroDao(tablePool, tableName,
        "TestRecord", afterManager, goodMigrationRecordAddField);

    // Create the new entities
    for (int i = 0; i < 10; ++i) {
      GenericRecord entity = createGenericEntity(i, goodMigrationRecordAddField);
      entity.put("fieldToAdd1", i);
      entity.put("fieldToAdd2", i);
      for (GenericRecord rec : (List<GenericRecord>) entity.get("field5")) {
        rec.put("subfield4", String.valueOf(i));
      }
      afterDao.put(entity);
    }

    // ensure the new entities are what we expect with get operations
    for (int i = 0; i < 10; ++i) {
      // When getting with old dao, expect no values for fieldToAdd
      GenericRecord rec = dao.get(createKey(dao.getPartitionStrategy(), i));
      compareEntitiesWithUtf8(i, rec);
      assertEquals(null, rec.get("fieldToAdd1"));
      assertEquals(null, rec.get("fieldToAdd2"));

      rec = afterDao.get(createKey(dao.getPartitionStrategy(), i));
      compareEntitiesWithUtf8(i, rec);
      assertEquals(i, rec.get("fieldToAdd1"));
      assertEquals(i, rec.get("fieldToAdd2"));
      for (GenericRecord innerRec : (List<GenericRecord>) rec.get("field5")) {
        assertEquals(String.valueOf(i), innerRec.get("subfield4").toString());
      }
    }

    // ensure the new entities are what we expect with scan operations
    int cnt = 0;
    EntityScanner<GenericRecord> entityScanner = dao.getScanner();
    try {
      for (GenericRecord entity : entityScanner) {
        compareEntitiesWithUtf8(cnt, entity);
        cnt++;
      }
      assertEquals(10, cnt);
    } finally {
      entityScanner.close();
    }
  }

  /**
   * GenericAvroDaos created without a entity schema (but with a manager) should
   * use the newest available
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testDynamicGenericDao() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<GenericRecord> dao = new GenericAvroDao(tablePool, tableName,
        "TestRecord", manager);

    manager.migrateSchema(tableName, "TestRecord", goodMigrationRecordAddField);

    SchemaManager afterManager = new DefaultSchemaManager(tablePool);
    Dao<GenericRecord> afterDao = new GenericAvroDao(tablePool, tableName,
        "TestRecord", afterManager);

    // Create an entity with each dao.
    PartitionKey key1 = createKey(dao.getPartitionStrategy(), 1);
    GenericRecord entity1 = createGenericEntity(1, testRecordv2);
    for (GenericRecord rec : (List<GenericRecord>) entity1.get("field5")) {
      rec.put("subfield4", new Utf8(String.valueOf(2)));
    }
    dao.put(entity1);
    PartitionKey key2 = createKey(dao.getPartitionStrategy(), 2);
    GenericRecord entity2 = createGenericEntity(2, goodMigrationRecordAddField);
    entity2.put("fieldToAdd1", 2);
    entity2.put("fieldToAdd2", 2);
    for (GenericRecord rec : (List<GenericRecord>) entity2.get("field5")) {
      rec.put("subfield4", new Utf8(String.valueOf(2)));
    }
    afterDao.put(entity2);

    // ensure the new entities are what we expect with get operations
    GenericRecord entity1After = dao.get(key1);
    compareEntitiesWithUtf8(1, entity1After);
    assertNull(entity1After.get("fieldToAdd1"));
    assertNull(entity1After.get("fieldToAdd2"));
    GenericRecord entity2After = dao.get(key2);
    compareEntitiesWithUtf8(2, entity2After);
    assertNull(entity2After.get("fieldToAdd1"));
    assertNull(entity2After.get("fieldToAdd2"));

    entity1After = afterDao.get(key1);
    compareEntitiesWithUtf8(1, entity1After);
    // These should have gotten default values this time.
    assertNotNull(entity1After.get("fieldToAdd1"));
    assertNotNull(entity1After.get("fieldToAdd2"));
    entity2After = afterDao.get(key2);
    compareEntitiesWithUtf8(2, entity2After);
    assertEquals(2, entity2After.get("fieldToAdd1"));
    assertEquals(2, entity2After.get("fieldToAdd2"));
  }

  @Test
  public void testIncrement() {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    Dao<TestRecord> dao = new SpecificAvroDao<TestRecord>(tablePool, tableName,
        "TestRecord", manager);

    TestRecord entity = createSpecificEntity(0);
    dao.put(entity);

    PartitionKey key = createKey(dao.getPartitionStrategy(), 0);
    dao.increment(key, "increment", 10);
    assertEquals(20L, (long) dao.get(key).getIncrement());

    dao.increment(key, "increment", 5);
    assertEquals(25L, (long) dao.get(key).getIncrement());
  }
  
  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigrationKeyField() throws Exception {
    badMigration(badMigrationRecordAddKeyField);
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigration1() throws Exception {
    badMigration(badMigrationRecordAddFieldNoDefault);
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigration2() throws Exception {
    badMigration(badMigrationRecordAddSubFieldNoDefault);
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigration3() throws Exception {
    badMigration(badMigrationRecordModifiedMapping);
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigrationDuplicateSchema() throws Exception {
    badMigration(testRecordv2);
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testBadMigrationIntToLong() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    manager.migrateSchema(tableName, "TestRecord", goodMigrationRecordAddField);
    manager.migrateSchema(tableName, "TestRecord", badMigrationRecordIntToLong);
  }

  @Test
  public void testGoodMigrations() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    manager.migrateSchema(tableName, "TestRecord", goodMigrationRecordAddField);
    manager.migrateSchema(tableName, "TestRecord",
        goodMigrationRecordRemoveField);
    manager.migrateSchema(tableName, "TestRecord",
        goodMigrationRecordAddSubField);
  }

  @Test
  public void testCreate() throws Exception {
    AvroKeyEntitySchemaParser parser = new AvroKeyEntitySchemaParser();
    // Clear out what was set up in before()
    Dao<ManagedSchema> managedDao = new SpecificAvroDao<ManagedSchema>(
        tablePool, "managed_schemas", managedRecordString, ManagedSchema.class);

    managedDao.delete(managedDao.getPartitionStrategy().partitionKey(tableName,
        "test"));

    SchemaManager manager = new DefaultSchemaManager(tablePool);
    try {
      manager.getEntityVersion(tableName, "test",
          parser.parseEntitySchema(testRecord));
      fail();
    } catch (SchemaNotFoundException e) {
      // This is what we expect
    }
    manager.createSchema(tableName, "test", testRecord,
        "com.cloudera.cdk.data.hbase.avro.impl.AvroKeyEntitySchemaParser",
        "com.cloudera.cdk.data.hbase.avro.impl.AvroKeySerDe",
        "com.cloudera.cdk.data.hbase.avro.impl.AvroEntitySerDe");
    assertEquals(
        0,
        manager.getEntityVersion(tableName, "test",
            parser.parseEntitySchema(testRecord)));
  }

  @Test(expected = IncompatibleSchemaException.class)
  public void testCannotCreateExisting() throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    manager.createSchema(tableName, "TestRecord", goodMigrationRecordAddField,
        "com.cloudera.cdk.data.hbase.avro.impl.AvroKeyEntitySchemaParser",
        "com.cloudera.cdk.data.hbase.avro.impl.AvroKeySerDe",
        "com.cloudera.cdk.data.hbase.avro.impl.AvroEntitySerDe");
  }

  private void badMigration(String badMigration) throws Exception {
    SchemaManager manager = new DefaultSchemaManager(tablePool);
    manager.migrateSchema(tableName, "TestRecord", badMigration);
  }
}
