package pro.dbro.airshare.crypto;

/**
 * Created by davidbrodsky on 10/22/14.
 */
@SuppressWarnings("WeakerAccess")
public class KeyPair {

    public final byte[] publicKey;
    public final byte[] secretKey;

    public KeyPair(byte[] publicKey, byte[] secretKey) {
        this.publicKey = publicKey;
        this.secretKey = secretKey;
    }
}
