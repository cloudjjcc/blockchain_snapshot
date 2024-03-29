package top.wangjc.blockchain_snapshot.service.impl;

import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Service;
import top.wangjc.blockchain_snapshot.document.OperationLogDocument;
import top.wangjc.blockchain_snapshot.document.TronAccountDocument;
import top.wangjc.blockchain_snapshot.dto.TronAccount;
import top.wangjc.blockchain_snapshot.dto.TronBlock;
import top.wangjc.blockchain_snapshot.repository.MongoRepositoryImpl;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TronSnapshotServiceImpl extends AbstractSnapshotService implements ApplicationListener<ContextClosedEvent> {
    @Value("${tron.httpAddr}")
    private String httpAddr;
    @Value("${tron.batchSize:100}")
    private Integer batchSize;
    @Value("${tron.startBlock:}")
    private Integer startBlock;
    @Value("${tron.endBlock:}")
    private Integer endBlock;

    private static final String INVALID_ADDRESS = "3078303030303030303030303030303030303030303030";

    private EnhanceHttpServiceImpl httpService;
    @Autowired
    private MongoRepositoryImpl mongoRepository;


    protected TronSnapshotServiceImpl() {
        super(ChainType.Tron, "tron_snapshot");
    }

    @Override
    protected void handleLog(OperationLogDocument operationLogEntity) {

    }

    /**
     * 获取批次区块信息
     *
     * @param batch
     * @return
     */
    @Override
    protected OperationLogDocument handleBatchBlock(List<Integer> batch) {
//        log.info("=======start handle {}-{} block ===========", batch.get(0), batch.get(batch.size() - 1));
        long s = System.currentTimeMillis();
        OperationLogDocument operationLogEntity = new OperationLogDocument(batch.get(0), batch.get(batch.size() - 1), chainType);
        Flowable.fromIterable(batch)
                .retry(3)
                .map(this::getBlockByNum)
                .subscribe(this::handleBlock, this::handleError, () -> {
                });
        return operationLogEntity;
    }

    private void handleBlock(TronBlock tronBlock) {
        Flowable.fromIterable(tronBlock.getTransactions())
                .map(this::getOwnerAddresses)
                .filter(address -> !foundAddress.contains(address))
                .retry(3)
                .map(this::getAccount)
                .map(TronAccountDocument::fromTronAccount)
                .toList()
                .subscribe(accounts -> {
                    mongoRepository.batchSave(accounts);
                    foundAddress.addAll(accounts.stream().map(account -> account.getAddress()).collect(Collectors.toList()));
                    increaseCounter();
                }, this::handleError);
    }

    /**
     * 从交易中查找账号
     *
     * @param transaction
     * @return
     */
    private String getOwnerAddresses(TronBlock.Transaction transaction) {
        return transaction.getRawData().getContract().stream().map(contract -> contract.getParameter().getValue().getOwnerAddress()).collect(Collectors.toList()).get(0);
    }

    /**
     * 获得任务起始区块
     *
     * @return
     */
    @Override
    public int getStartBlock() {
        return startBlock;
    }

    @Override
    public int getEndBlock() {
        return endBlock;
    }

    @Override
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 获取账户信息
     *
     * @param address
     * @return
     */
    private TronAccount getAccount(String address) throws IOException {
        TronAccount account = new TronAccount(address);
        if (!INVALID_ADDRESS.equals(address)) {
            Request request = new Request.Builder().get().url(httpAddr + "/wallet/getaccount?address=" + address).build();
            account = httpService.sendCustomRequest(request, TronAccount.class);
        }
        return account;
    }

    private TronBlock getBlockByNum(Integer number) throws IOException {
        Request request = new Request.Builder().get().url(httpAddr + "/wallet/getblockbynum?num=" + number).build();
        return httpService.sendCustomRequest(request, TronBlock.class);
    }

    @Override
    protected void doInit() {
        super.doInit();
        httpService = EnhanceHttpServiceImpl.createDefault(httpAddr);
    }

    @Override
    protected void doClose() {
        super.doClose();
        try {
            httpService.close();
        } catch (IOException e) {
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        stop();
    }
}
