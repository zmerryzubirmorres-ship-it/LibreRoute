package io.github.libreroute.admin;

/** Real child process: fills stderr before returning a JSON response. */
public class NativeAdminProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("timeout")) {
            Thread.sleep(10000);
            return;
        }
        System.in.readAllBytes();
        System.err.print("diagnostic\n".repeat(20000));
        System.err.flush();
        if (args[0].equals("overflow")) {
            System.out.print("x".repeat(600000));
        } else {
            System.out.print("{\"status\":\"ready\"}");
        }
    }
}
