package com.yuedong.file.auth;

/**
 * 管理员身份的最小快照。
 *
 * <p>用 record 而不是 Map：字段固定、类型明确，调用方拿不到不存在的 key，
 * 也不用在编译期之外猜"到底是 adminId 还是 userId"。</p>
 */
public record AdminIdentity(Long adminId, String role) {
}
