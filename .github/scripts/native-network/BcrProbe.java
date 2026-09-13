import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.net.ssl.HttpsURLConnection;

/** Observes the two module fetches without changing DNS, TLS, or routing policy. */
public final class BcrProbe {
  private static final String[] URLS = {
    "https://bcr.bazel.build/modules/buildozer/8.5.1/MODULE.bazel",
    "https://bcr.bazel.build/modules/platforms/1.0.0/MODULE.bazel"
  };
  private static final int MAX_BYTES = 1024 * 1024;

  public static void main(String[] args) throws Exception {
    System.out.println("java.version=" + System.getProperty("java.version"));
    System.out.println("java.vendor=" + System.getProperty("java.vendor"));
    System.out.println("preferIPv6Addresses=" + System.getProperty("java.net.preferIPv6Addresses"));
    System.out.println("preferIPv4Stack=" + System.getProperty("java.net.preferIPv4Stack"));
    if (args.length == 1 && args[0].equals("--describe")) {
      for (String url : URLS) {
        System.out.println(url);
      }
      return;
    }
    if (args.length != 0) {
      throw new IllegalArgumentException("Only --describe is supported");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName("bcr.bazel.build")) {
        System.out.println(
            "DNS " + address.getClass().getSimpleName() + " " + address.getHostAddress());
      }
    } catch (Exception e) {
      e.printStackTrace(System.out);
    }
    for (String url : URLS) {
      long start = System.nanoTime();
      HttpsURLConnection connection = null;
      System.out.println("GET " + url);
      try {
        connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        System.out.println("status=" + connection.getResponseCode());
        try (var input = connection.getInputStream()) {
          byte[] body = input.readNBytes(MAX_BYTES + 1);
          if (body.length > MAX_BYTES) {
            throw new IllegalStateException("Response exceeds 1 MiB");
          }
          System.out.println("bytes=" + body.length);
          System.out.println(
              "sha256="
                  + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        }
      } catch (Exception e) {
        e.printStackTrace(System.out);
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
        System.out.println("elapsedMillis=" + (System.nanoTime() - start) / 1_000_000);
      }
    }
  }
}
