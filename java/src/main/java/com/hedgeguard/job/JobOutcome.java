package com.hedgeguard.job;

/**
 * 任务执行结果（{@code 验收标准 4} 的可观测载体）。
 *
 * <p>{@link Status} 区分「未到点 / 重复短路 / 重试上限耗尽 / 成功 / 失败」，供运维与单测断言；
 * 失败态保留异常信息以便告警；重试上限耗尽表示该时间片已停止自动重试（见
 * {@link JobIdempotencyGuard}）。
 */
public final class JobOutcome {

    private JobOutcome() {
    }

    /** 单次触发的处置状态。 */
    public enum Status {
        /** 未到触发时点，未执行。 */
        NOT_DUE,
        /** 幂等守卫判定重复（已成功 / 执行中），短路未执行。 */
        SKIPPED_DUPLICATE,
        /**
         * 幂等守卫判定该时间片连续失败次数已达重试上限，停止自动重试：
         * 未执行、未调用 L3 端口、不再告警（等待人工介入）。
         */
        SKIPPED_RETRY_LIMIT,
        /** 执行成功。 */
        SUCCESS,
        /** 执行失败且已告警，状态已释放（连续失败未达上限时可重入重试）。 */
        FAILED
    }

    /**
     * 由 L3 服务端口返回的业务结果摘要。
     *
     * <p>刻意只承载「条数 + 说明」，不承载 DAO 实体 / 连接，保证 {@code job} 包零 DAO 引用。
     */
    public static final class Summary {
        private final int affected;
        private final String detail;

        /**
         * 构造。
         *
         * @param affected 影响 / 命中条数
         * @param detail   说明文案
         */
        public Summary(int affected, String detail) {
            this.affected = affected;
            this.detail = detail == null ? "" : detail;
        }

        /** 空结果（无数据，不算失败）。 */
        public static Summary empty() {
            return new Summary(0, "");
        }

        /** 影响 / 命中条数。 */
        public int affected() {
            return affected;
        }

        /** 说明文案。 */
        public String detail() {
            return detail;
        }
    }

    /** 一次触发的完整结果。 */
    public static final class Result {
        private final JobType type;
        private final String bucket;
        private final Status status;
        private final int affected;
        private final String detail;
        private final Throwable cause;

        /**
         * 构造。
         *
         * @param type     任务类型
         * @param bucket   幂等时间片
         * @param status   处置状态
         * @param affected 影响条数
         * @param detail   说明
         * @param cause    失败原因（成功时为 {@code null}）
         */
        public Result(JobType type, String bucket, Status status, int affected,
                      String detail, Throwable cause) {
            this.type = type;
            this.bucket = bucket;
            this.status = status;
            this.affected = affected;
            this.detail = detail == null ? "" : detail;
            this.cause = cause;
        }

        /** 未到点。 */
        public static Result notDue(JobType type) {
            return new Result(type, "", Status.NOT_DUE, 0, "未到触发时点", null);
        }

        /** 重复短路（幂等守卫）。 */
        public static Result duplicate(JobType type, String bucket) {
            return new Result(type, bucket, Status.SKIPPED_DUPLICATE, 0,
                    "幂等守卫：该时间片已处理，跳过以避免重复结算", null);
        }

        /**
         * 重试上限耗尽（连续失败达上限，本时间片不再自动重试）。
         *
         * @param type        任务类型
         * @param bucket      幂等时间片
         * @param attempts    已连续失败次数
         * @param maxAttempts 重试上限
         * @return 处置结果
         */
        public static Result retryLimitExhausted(JobType type, String bucket,
                                                 int attempts, int maxAttempts) {
            return new Result(type, bucket, Status.SKIPPED_RETRY_LIMIT, 0,
                    "幂等守卫：连续失败 " + attempts + " 次已达重试上限 " + maxAttempts
                            + " 次，本时间片停止自动重试（需人工介入）", null);
        }

        /** 成功。 */
        public static Result success(JobType type, String bucket, Summary summary) {
            return new Result(type, bucket, Status.SUCCESS,
                    summary == null ? 0 : summary.affected(),
                    summary == null ? "" : summary.detail(), null);
        }

        /** 失败（已由调度器告警）。 */
        public static Result failed(JobType type, String bucket, Throwable cause) {
            return new Result(type, bucket, Status.FAILED, 0,
                    cause == null ? "执行失败" : cause.getMessage(), cause);
        }

        /** 任务类型。 */
        public JobType type() {
            return type;
        }

        /** 幂等时间片。 */
        public String bucket() {
            return bucket;
        }

        /** 处置状态。 */
        public Status status() {
            return status;
        }

        /** 影响条数。 */
        public int affected() {
            return affected;
        }

        /** 说明。 */
        public String detail() {
            return detail;
        }

        /** 失败原因。 */
        public Throwable cause() {
            return cause;
        }
    }
}
