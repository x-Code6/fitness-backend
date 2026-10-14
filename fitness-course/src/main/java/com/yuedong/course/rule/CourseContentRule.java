package com.yuedong.course.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 展示型课程（{@code course_kind=CONTENT}）的规则说明：没有购买、没有凭证、没有预约。
 */
@Component
public class CourseContentRule implements RuleDescribeStrategy {

    @Override
    public String scene() {
        return RuleScene.COURSE_CONTENT.name();
    }

    @Override
    public List<String> describe(RuleContext context) {
        List<String> texts = new ArrayList<>();
        texts.add("本课程为免费跟练内容，无需购买、无需预约教练");
        texts.add("可发表评论与回复；评论可删除，删除后不可恢复");
        texts.add("跟练完成度按视频进度记录，同一视频只记一次最高进度");
        return texts;
    }

    @Override
    public String summary(RuleContext context) {
        return "免费跟练 · 无需购买";
    }
}
