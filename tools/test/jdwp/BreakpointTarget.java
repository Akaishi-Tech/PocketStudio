public final class BreakpointTarget {
    private static int work(int input, String text, Object optional) {
        int value = input + 1; // BREAK_HERE
        return value * 2;      // STEP_HERE
    }

    public static void main(String[] args) throws Exception {
        System.out.println("READY");
        System.in.read();
        System.out.println("RESULT " + work(20, "test\ninput", null));
    }
}
