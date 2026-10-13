package com.yuedong.adminserver.util;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 图形验证码生成器（零依赖：Graphics2D 手绘 + Base64 PNG）。
 *
 * <p>防刷设计：4 位字符（剔除易混淆的 0/O/1/I），干扰线 + 随机颜色，
 * Redis 一次一用由 Controller 层负责。</p>
 */
public class CaptchaGenerator {

    /** 字符集（剔除 0/O/1/I 等易混淆字符） */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 验证码生成结果：code 存 Redis，imageBase64 返回给前端 */
    public record CaptchaResult(String code, String imageBase64) {
    }

    /** 生成 4 位验证码图片（96x36） */
    public static CaptchaResult generate() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }

        int width = 96, height = 36;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 背景
        g.setColor(new Color(245, 246, 250));
        g.fillRect(0, 0, width, height);

        // 干扰线（3 条随机弧线）
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(RANDOM.nextInt(200), RANDOM.nextInt(200), RANDOM.nextInt(200)));
            g.setStroke(new BasicStroke(1.2f));
            g.drawArc(RANDOM.nextInt(width / 2), RANDOM.nextInt(height / 2),
                    width / 2 + RANDOM.nextInt(40), height / 2 + RANDOM.nextInt(20),
                    RANDOM.nextInt(360), RANDOM.nextInt(360));
        }

        // 逐字符绘制（随机颜色 + 轻微上下浮动）
        for (int i = 0; i < 4; i++) {
            g.setColor(new Color(RANDOM.nextInt(120), RANDOM.nextInt(120), RANDOM.nextInt(120)));
            g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 22));
            g.drawString(String.valueOf(code.charAt(i)), 10 + i * 22,
                    26 + RANDOM.nextInt(6) - 3);
        }
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            String base64 = "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(out.toByteArray());
            return new CaptchaResult(code.toString(), base64);
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }
}
