package top.wangjc.blockchain_snapshot.entity;

import lombok.Data;
import org.apache.logging.log4j.util.Strings;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.math.BigInteger;
import java.util.Date;

@Data
@Entity(name = "eth_account")
public class EthAccountEntity {
    private static final BigInteger ETH_LOW = BigInteger.valueOf(1000000000000000000l);
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /**
     * 地址
     */
    private String address;
    /**
     * usdt 余额
     */
    private BigInteger usdtBalance;
    /**
     * 余额低位
     */
    private BigInteger ethBalanceLow;
    /**
     * 余额高位
     */
    private BigInteger ethBalanceHigh;

    /**
     * 更新时间
     */
    private Date updateDate;
    /**
     * 更新时的区块高度
     */
    private Integer updateBlockHeight;

    public BigInteger getEthBalance() {
        return ethBalanceHigh.multiply(ETH_LOW).add(ethBalanceLow);
    }

    public void setEthBalance(BigInteger ethBalance) {
        this.ethBalanceHigh = ethBalance.divide(ETH_LOW);
        this.ethBalanceLow = ethBalance.subtract(ethBalanceHigh.multiply(ETH_LOW));
    }

    public EthAccountEntity() {
        this(Strings.EMPTY);
    }

    public EthAccountEntity(String address) {
        this(address, BigInteger.ZERO);
    }

    public EthAccountEntity(String address, BigInteger balance) {
        this(address, balance, BigInteger.ZERO);
    }

    public EthAccountEntity(String address, BigInteger ethBalance, BigInteger usdtBalance) {
        this(address, ethBalance, usdtBalance, 0);
    }

    public EthAccountEntity(String address, BigInteger ethBalance, BigInteger usdtBalance, int updateBlockHeight) {
        this.address = address;
        this.usdtBalance = usdtBalance;
        setEthBalance(ethBalance);
        this.updateBlockHeight = updateBlockHeight;
    }
}
