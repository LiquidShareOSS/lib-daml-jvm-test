package com.digitalasset.testing.ledger;

import com.digitalasset.ledger.api.v1.CommandServiceGrpc;
import com.digitalasset.ledger.api.v1.CommandServiceOuterClass;
import com.digitalasset.ledger.api.v1.CommandsOuterClass;
import com.digitalasset.ledger.api.v1.LedgerOffsetOuterClass;
import com.digitalasset.ledger.api.v1.TransactionServiceGrpc;
import com.digitalasset.ledger.api.v1.TransactionServiceOuterClass;
import com.digitalasset.testing.comparator.MessageTester;
import com.digitalasset.testing.comparator.ledger.JContractCreated;
import com.digitalasset.testing.ledger.clock.TimeProvider;
import com.digitalasset.testing.store.InMemoryMessageStorage;
import com.digitalasset.testing.store.ValueStore;
import com.digitalasset.testing.utils.ContractWithId;

import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.ContractId;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.FiltersByParty;
import com.daml.ledger.javaapi.data.GetTransactionsRequest;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.LedgerOffset;
import com.daml.ledger.javaapi.data.NoFilter;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Record;
import com.daml.ledger.javaapi.data.TreeEvent;
import com.daml.ledger.javaapi.data.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

// TODO(lt) port over interactionLogger and Dump.dump
public class DefaultLedgerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(DefaultLedgerAdapter.class);

  private static final String APP_ID = "func-test";

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TTL = Duration.ofSeconds(10);

  private static final String ChannelName = "ledger";
  private static final String FTSandboxHost =
      Optional.ofNullable(System.getProperty("ft.sandbox.hostname"))
          .filter(s -> !s.trim().isEmpty())
          .orElse("localhost");

  private static final int FTSandboxPort = Integer.getInteger("ft.sandbox.port", 6865);
  private static final String wireLogger = "LEDGER.WIRE";
  private static final String interactionLogger = "LEDGER.INTERACTION";

  // TODO(lt) figure out an interface for interacting with the value store
  public final ValueStore valueStore;
  private final Supplier<TimeProvider> timeProviderFactory;

  private Duration timeout = TIMEOUT;
  private TimeProvider timeProvider;
  private ManagedChannel channel;
  private String ledgerId;
  private LedgerOffset startOffset;
  private Map<String, InMemoryMessageStorage<TreeEvent>> storageByParty;

  public DefaultLedgerAdapter(
      ValueStore valueStore,
      String ledgerId,
      ManagedChannel channel,
      Supplier<TimeProvider> timeProviderFactory) {
    this.valueStore = valueStore;
    this.ledgerId = ledgerId;
    this.channel = channel;
    this.timeProviderFactory = timeProviderFactory;
  }

  private static CodedInputStream toCodedInputStream(ByteString input) {
    CodedInputStream cos = CodedInputStream.newInstance(input.asReadOnlyByteBuffer());
    cos.setRecursionLimit(1000);
    return cos;
  }

  private static Timestamp toProtobufTimestamp(Instant instant)
      throws InvalidProtocolBufferException {
    return Timestamp.newBuilder()
        .setSeconds(instant.toEpochMilli() / 1000)
        .setNanos(Long.valueOf((instant.toEpochMilli() % 1000) * 1000).intValue())
        .build();
  }

  public void start(String... parties) {
    start(parties, LedgerOffset.LedgerBegin.getInstance());
  }

  public synchronized void start(String[] explicitParties, LedgerOffset suggestStartOffset) {
    logger.info("Starting Ledger Adapter");
    this.startOffset = initStartOffset(suggestStartOffset);
    this.timeProvider = timeProviderFactory.get();
    storageByParty = new ConcurrentHashMap<>();
    for (String explicitParty : explicitParties) {
      getStorage(explicitParty);
    }
    logger.info("Ledger Adapter Started");
  }

  public synchronized void stop() throws InterruptedException {
    if (channel != null) {
      logger.info("Stopping Ledger Adapter");
      channel.shutdown().awaitTermination(5L, TimeUnit.SECONDS);
      channel = null;
      logger.info("Ledger Adapter stopped");
    }
  }

  public synchronized void createContract(Party party, Identifier templateId, Record payload)
      throws InvalidProtocolBufferException {
    logger.debug("Attempting to create a contract {}", templateId);
    submit(party, new CreateCommand(templateId, payload));
  }

  public synchronized void exerciseChoice(
      Party party, Identifier templateId, ContractId contractId, String choice, Value payload)
      throws InvalidProtocolBufferException {
    exerciseChoice(party, new ExerciseCommand(templateId, contractId.getValue(), choice, payload));
  }

  public void exerciseChoice(Party party, ExerciseCommand exerciseCommand)
      throws InvalidProtocolBufferException {
    logger.debug(
        "Attempting to create exercise {} on {} in contract {}",
        exerciseCommand.getChoice(),
        exerciseCommand.getTemplateId(),
        exerciseCommand.getContractId());
    submit(party, exerciseCommand);
  }

  public TreeEvent observeEvent(String party, MessageTester<TreeEvent> eventTester) {
    return getStorage(party).observe(timeout, eventTester);
  }

  public void assertDidntHappen(String party, MessageTester<TreeEvent> eventTester) {
    getStorage(party).assertDidntHappen(eventTester);
  }

  private synchronized InMemoryMessageStorage<TreeEvent> getStorage(String party) {
    // ensureStarted();
    if (!storageByParty.containsKey(party)) {
      InMemoryMessageStorage<TreeEvent> storage = initStorageAndStartListening(party);
      storageByParty.put(party, storage);
    }
    return storageByParty.get(party);
  }

  private LedgerOffset initStartOffset(LedgerOffset suggestStartOffset) {
    if (LedgerOffset.LedgerEnd.getInstance().equals(suggestStartOffset)) {
      LedgerOffsetOuterClass.LedgerOffset endOffset =
          TransactionServiceGrpc.newBlockingStub(channel)
              .getLedgerEnd(
                  TransactionServiceOuterClass.GetLedgerEndRequest.newBuilder()
                      .setLedgerId(ledgerId)
                      .build())
              .getOffset();

      return LedgerOffset.fromProto(endOffset);
    }
    return suggestStartOffset;
  }

  private InMemoryMessageStorage<TreeEvent> initStorageAndStartListening(String party) {
    InMemoryMessageStorage<TreeEvent> storage =
        new InMemoryMessageStorage<>(ChannelName, valueStore);
    StreamObserver<TransactionServiceOuterClass.GetTransactionTreesResponse> observer =
        new StreamObserver<TransactionServiceOuterClass.GetTransactionTreesResponse>() {
          public void onNext(TransactionServiceOuterClass.GetTransactionTreesResponse response) {
            onMessage(response, party, storage);
          }

          public void onError(Throwable t) {
            logger.error("Error occurred in stream handler for party " + party, t);
          }

          public void onCompleted() {
            logger.trace("Stream processing completed for party {}", party);
          }
        };

    GetTransactionsRequest request =
        new GetTransactionsRequest(
            ledgerId,
            startOffset,
            new FiltersByParty(Collections.singletonMap(party, NoFilter.instance)),
            true);

    TransactionServiceGrpc.newStub(channel).getTransactionTrees(request.toProto(), observer);
    return storage;
  }

  private synchronized void onMessage(
      TransactionServiceOuterClass.GetTransactionTreesResponse response,
      String party,
      InMemoryMessageStorage<TreeEvent> storage) {
    if (channel != null) {
      response
          .getTransactionsList()
          .forEach(
              tree -> {
                tree.getEventsByIdMap()
                    .values()
                    .forEach(
                        event -> {
                          // Dump.dump(wireLogger, );
                          storage.onMessage(TreeEvent.fromProtoTreeEvent(event));
                        });
              });
    }
  }

  private void submit(Party party, Command command) throws InvalidProtocolBufferException {
    Instant let = timeProvider.getCurrentTime();
    Instant mrt = let.plus(TTL);
    String cmdId = UUID.randomUUID().toString();

    CommandServiceGrpc.newBlockingStub(channel)
        .submitAndWait(
            CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder()
                .setCommands(
                    CommandsOuterClass.Commands.newBuilder()
                        .setLedgerId(ledgerId)
                        .setWorkflowId(String.format("%s:%s", APP_ID, cmdId))
                        .setApplicationId(APP_ID)
                        .setCommandId(cmdId)
                        .setParty(party.getValue())
                        .setLedgerEffectiveTime(toProtobufTimestamp(let))
                        .setMaximumRecordTime(toProtobufTimestamp(mrt))
                        .addCommands(command.toProtoCommand()))
                .build());
  }

  public Instant getCurrentTime() {
    return timeProvider.getCurrentTime();
  }

  public void setCurrentTime(Instant time) {
    timeProvider.setCurrentTime(time);
  }

  private static final String key = "internal-cid-query";
  private static final String recordKey = "internal-recordKey";

  public <Cid> Cid getCreatedContractId(
      Party party, Identifier identifier, Record arguments, Function<String, Cid> ctor) {
    observeEvent(
        party.getValue(),
        JContractCreated.expectContractWithArguments(
            identifier, "{CAPTURE:" + key + "}", arguments));

    String val = valueStore.get(key).getContractId();
    Cid cid = ctor.apply(val);
    valueStore.remove(key);
    return cid;
  }

  public <Cid> Cid getCreatedContractId(
      Party party, Identifier identifier, Function<String, Cid> ctor) {
    observeEvent(
        party.getValue(), JContractCreated.expectContract(identifier, "{CAPTURE:" + key + "}"));
    String val = valueStore.get(key).getContractId();
    Cid cid = ctor.apply(val);
    valueStore.remove(key);
    return cid;
  }

  public <Cid> ContractWithId<Cid> getMatchedContract(
      Party party, Identifier identifier, Function<String, Cid> ctor) {
    try {
      observeEvent(
          party.getValue(),
          JContractCreated.capture(identifier, "{CAPTURE:" + key + "}", recordKey));

      String contractId = valueStore.get(key).getContractId();
      Cid cid = ctor.apply(contractId);
      Value record =
          Value.fromProto(
              com.digitalasset.ledger.api.v1.ValueOuterClass.Value.parseFrom(
                  valueStore.get(recordKey).toByteArray()));
      valueStore.remove(key);
      valueStore.remove(recordKey);
      return new ContractWithId<>(cid, record);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Makes sure that a set of contracts are present on the ledger. The contracts with the given
   * templateIds are fetched one-by-one and matched against the given set of predicates. If all
   * predicates are satisfied the method returns with true.
   *
   * <p>If `exact` is true, each incoming contract must match a predicate, otherwise the method will
   * return false.
   *
   * <p>Note: the ledger cursor will be moved during the execution of this method.
   *
   * @return true if all predicates were satisfied, false if a non-matching contract was observed in
   *     exact mode.
   */
  public <Contract> boolean observeMatchingContracts(
      Party party,
      Identifier templateId,
      Function<Value, Contract> ctor,
      boolean exact,
      Predicate<Contract>... predicates) {
    Set<Predicate<Contract>> predicateSet = new HashSet<>(Arrays.asList(predicates));
    while (!predicateSet.isEmpty()) {
      ContractWithId<String> contractWithId = getMatchedContract(party, templateId, i -> i);
      Contract contract = ctor.apply(contractWithId.record);
      Optional<Predicate<Contract>> predicateMatch =
          predicateSet.stream().filter(p -> p.test(contract)).findFirst();
      if (predicateMatch.isPresent()) {
        predicateSet.remove(predicateMatch.get());
      } else {
        if (exact) return false;
      }
    }
    return true;
  }
}
