package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.github.catvod.spider.entity.DanmakuItem;
import com.github.catvod.spider.danmu.SharedPreferencesService;
import com.github.catvod.net.OkHttp;
import okhttp3.Response;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DanmakuUIHelper {

    // 定义统一的颜色常量 - 现代化配色方案
    private static final int PRIMARY_COLOR = 0xFF007AFF;        // 主色调蓝色 - 第一层(页签)
    private static final int PRIMARY_DARK = 0xFF0056B3;         // 深蓝色
    private static final int PRIMARY_LIGHT = 0xFF80BFFF;        // 浅蓝色
    private static final int SECONDARY_COLOR = 0xFFFF9500;      // 橙色 - 第二层(分组)
    private static final int SECONDARY_DARK = 0xFFCC7700;       // 深橙色
    private static final int SECONDARY_LIGHT = 0xFFFFB84D;      // 浅橙色
    private static final int TERTIARY_COLOR = 0xFF34C759;       // 绿色 - 第三层(结果)
    private static final int TERTIARY_DARK = 0xFF2D9D4F;        // 深绿色
    private static final int TERTIARY_LIGHT = 0xFF5ECA7C;       // 浅绿色
    private static final int ACCENT_COLOR = 0xFFFF9500;         // 橙色强调
    private static final int ACCENT_LIGHT = 0xFFFFB84D;         // 浅橙色
    private static final int SUCCESS_COLOR = 0xFF34C759;        // 绿色成功
    private static final int TEXT_PRIMARY = 0xFF1A1A1A;         // 深灰色主文本
    private static final int TEXT_SECONDARY = 0xFF666666;       // 中灰色副文本
    private static final int TEXT_TERTIARY = 0xFF999999;        // 浅灰色辅助文本
    private static final int BACKGROUND_LIGHT = 0xFFF8F9FA;     // 浅灰白背景
    private static final int BACKGROUND_WHITE = 0xFFFFFFFF;     // 纯白背景
    private static final int BORDER_COLOR = 0xFFE0E0E0;         // 边框色
    private static final int BORDER_LIGHT = 0xFFF0F0F0;         // 浅边框色
    private static final int FOCUS_HIGHLIGHT_COLOR = 0xFF80BFFF;// 焦点高亮色
    private static final int SHADOW_COLOR = 0x1A000000;         // 阴影色
    private static final int GRAY_INACTIVE = 0xFFBBBBBB;        // 灰色(未选中状态)

    // ========== 电视端焦点高亮专用颜色 ==========
    private static final int TV_FOCUS_BORDER_COLOR = 0xFF00D4FF;  // 高对比度青色边框 - 电视端焦点
    private static final int TV_FOCUS_BG_COLOR = 0xFF007AFF;      // 焦点背景色 - 蓝色
    private static final int TV_FOCUS_TEXT_COLOR = 0xFFFFFFFF;    // 焦点文字颜色 - 白色
    private static final int TV_FOCUS_GLOW_COLOR = 0xFF00D4FF;    // 发光效果颜色
    private static final int TV_INPUT_FOCUS_BG = 0xFFE3F2FD;      // 输入框焦点背景 - 浅蓝
    private static final int TV_INPUT_FOCUS_BORDER = 0xFF007AFF;  // 输入框焦点边框

    // ========== 深色透明主题颜色 (from DanmakuUIHelper3) ==========
    private static final int DARK_BG_PRIMARY =  0xCC000000;     // 主背景色 - 半透明黑色
    private static final int DARK_BG_SECONDARY = 0x33FFFFFF;   // 次级背景色 - 更透明
    private static final int DARK_BG_TERTIARY = 0x1AFFFFFF;    // 三级背景色 - 更透明
    private static final int DARK_TEXT_PRIMARY = 0xFFFFFFFF;   // 主文本色 - 白色
    private static final int DARK_TEXT_SECONDARY = 0xFFCCCCCC; // 次文本色 - 浅灰色
    private static final int DARK_TEXT_TERTIARY = 0xFF999999;  // 三级文本色 - 中灰色
    private static final int DARK_BORDER = 0x44FFFFFF;         // 边框色 - 浅白透明
    private static final int DARK_HIGHLIGHT = 0xFF007AFF;      // 高亮色 - 蓝色
    private static final int DARK_HIGHLIGHT_DARK = 0xFF0056B3; // 深高亮色 - 深蓝
    private static final int DARK_HIGHLIGHT_LIGHT = 0xFF80BFFF;// 浅高亮色 - 浅蓝
    private static final int DARK_INACTIVE = 0x55444444;       // 非激活状态 - 深灰半透明

    // 功能色（深色透明版本）
    private static final int DARK_PRIMARY_COLOR = 0x99007AFF;        // 页签蓝色
    private static final int DARK_PRIMARY_DARK = 0xCC0056B3;         // 深蓝色
    private static final int DARK_PRIMARY_LIGHT = 0xCC80BFFF;        // 浅蓝色
    private static final int DARK_SECONDARY_COLOR = 0xAAFF9500;      // 分组橙色 (translucent orange)
    private static final int DARK_SECONDARY_DARK = 0xCCCC7700;       // 深橙色 (darker translucent orange)
    private static final int DARK_SECONDARY_LIGHT = 0xCCFFB84D;      // 浅橙色
    private static final int DARK_TERTIARY_COLOR = 0xAA34C759;       // 结果绿色 (translucent green)
    private static final int DARK_TERTIARY_DARK = 0xCC2D9D4F;        // 深绿色 (darker translucent green)
    private static final int DARK_TERTIARY_LIGHT = 0x99BBBBBB;       // 浅绿色
    private static final int DARK_ACCENT_COLOR = 0xAAFF9500;         // 橙色强调
    private static final int DARK_ACCENT_LIGHT = 0xCCFFB84D;         // 浅橙色

    // ========== 模板专用色 ==========
    private static final int ACCENT_T2 = 0xFFFF9F0A;      // 模板二 暖橙
    private static final int ACCENT_T3 = 0xFF35C958;      // 模板三 翠绿
    private static final int ACCENT_T4 = 0xFF0A84FF;      // 模板四 亮蓝
    private static final int DARK_BG_SYSTEM = 0xE61C1C1E; // 深色系统黑
    private static final int DARK_TAB_BG = 0xFF2C2C2E;    // 深色页签底
    private static final int DARK_GRID_ITEM = 0xFF2C2C2E; // 深色网格项底
    private static final int DARK_GRID_BORDER = 0xFF38383A;// 深色网格边框
    private static final int LIGHT_GRID_BORDER = 0xFFE5E5EA;// 浅色网格边框
    private static final int TAB_BG_T1 = 0xFFF5F5F7;       // 模板一 页签底
    private static final int ITEM_BG_T1 = 0xFFF2F2F7;      // 模板一 列表项底
    private static final int REVERSE_GRAY = 0xFF8E8E93;    // 正序灰态


    /**
     * 排序状态标记 (false=正序, true=倒序)
     */
    private static boolean isReversed;
    /**
     * 当前选中的标签索引
     */
    private static List<DanmakuItem> currentItems = new ArrayList<>();


    // 显示配置对话框
    public static void showConfigDialog(Context ctx) {
        // 添加检查
        if (!(ctx instanceof Activity)) {
            DanmakuSpider.log("错误：Context不是Activity");
            return;
        }
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) {
            DanmakuSpider.log("Activity已销毁或正在销毁，不显示配置对话框");
            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 在创建对话框前再次检查状态
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        DanmakuSpider.log("Activity已销毁，不显示配置对话框");
                        return;
                    }

                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                    LinearLayout mainLayout = new LinearLayout(activity);
                    mainLayout.setOrientation(LinearLayout.VERTICAL);
                    mainLayout.setBackgroundColor(BACKGROUND_WHITE);
                    mainLayout.setPadding(dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20));

                    // 标题 - 增强视觉效果
                    TextView title = new TextView(activity);
                    title.setText("Leo弹幕配置");
                    title.setTextSize(24);
                    title.setTextColor(PRIMARY_COLOR);
                    title.setGravity(Gravity.CENTER);
                    title.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 20));
                    title.setTypeface(null, android.graphics.Typeface.BOLD);
                    mainLayout.addView(title);

                    // 副标题说明
                    TextView subtitle = new TextView(activity);
                    subtitle.setText("配置弹幕搜索API地址");
                    subtitle.setTextSize(13);
                    subtitle.setTextColor(TEXT_SECONDARY);
                    subtitle.setGravity(Gravity.CENTER);
                    subtitle.setPadding(0, 0, 0, dpToPx(activity, 16));
                    mainLayout.addView(subtitle);

                    // IP地址提示 - 改进样式
                    TextView ipInfo = new TextView(activity);
                    String ip = NetworkUtils.getLocalIpAddress();
//                ipInfo.setText("Web配置: http://" + ip + ":9810");
                    ipInfo.setTextSize(13);
                    ipInfo.setTextColor(ACCENT_COLOR);
                    ipInfo.setGravity(Gravity.CENTER);
                    ipInfo.setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 12), dpToPx(activity, 12));
                    ipInfo.setBackgroundColor(0xFFFFF8E1);
                    mainLayout.addView(ipInfo);

                    // API输入框容器 - 改进样式
                    LinearLayout inputContainer = new LinearLayout(activity);
                    inputContainer.setOrientation(LinearLayout.VERTICAL);
                    inputContainer.setBackgroundColor(BORDER_LIGHT);
                    inputContainer.setPadding(dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2));
                    LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    containerParams.setMargins(0, dpToPx(activity, 12), 0, dpToPx(activity, 12));
                    inputContainer.setLayoutParams(containerParams);

                    DanmakuConfig config = DanmakuConfigManager.getConfig(activity);

                    EditText apiInput = new EditText(activity);
                    apiInput.setText(TextUtils.join("\n", config.getApiUrls()));
                    apiInput.setHint("每行一个API地址\n例如: https://example.com/danmu");
                    apiInput.setMinLines(4);
                    apiInput.setMaxLines(7);
                    apiInput.setBackgroundColor(BACKGROUND_WHITE);
                    apiInput.setTextColor(TEXT_PRIMARY);
                    apiInput.setTextSize(13);
                    apiInput.setPadding(dpToPx(activity, 12), dpToPx(activity, 12), dpToPx(activity, 12), dpToPx(activity, 12));
                    apiInput.setHintTextColor(TEXT_TERTIARY);

                    inputContainer.addView(apiInput);
                    mainLayout.addView(inputContainer);

                    // 分割线 - 改进样式
                    View divider = new View(activity);
                    divider.setBackgroundColor(BORDER_LIGHT);
                    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 1));
                    dividerParams.setMargins(0, dpToPx(activity, 16), 0, dpToPx(activity, 16));
                    divider.setLayoutParams(dividerParams);
                    mainLayout.addView(divider);

                    // 按钮布局 - 改进设计
                    LinearLayout btnLayout = new LinearLayout(activity);
                    btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                    btnLayout.setGravity(Gravity.CENTER);

                    Button saveBtn = createStyledButton(activity, "保存", PRIMARY_COLOR);
                    Button clearBtn = createStyledButton(activity, "清空缓存", ACCENT_COLOR);
                    Button lpConfigBtn = createStyledButton(activity, "布局", ACCENT_COLOR);
                    Button cancelBtn = createStyledButtonWithBorder(activity, "取消", PRIMARY_COLOR);

                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                            0, dpToPx(activity, 44), 1);
                    btnParams.setMargins(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);

                    saveBtn.setLayoutParams(btnParams);
                    clearBtn.setLayoutParams(btnParams);
                    lpConfigBtn.setLayoutParams(btnParams);
                    cancelBtn.setLayoutParams(btnParams);

                    btnLayout.addView(saveBtn);
                    btnLayout.addView(clearBtn);
                    btnLayout.addView(lpConfigBtn);
                    btnLayout.addView(cancelBtn);

                    mainLayout.addView(btnLayout);

                    builder.setView(mainLayout);
                    AlertDialog dialog = builder.create();
                    dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            dpToPx(activity, 600)); // 设置固定高度

                    // 按钮事件
                    saveBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String text = apiInput.getText().toString();
                            String[] lines = text.split("\n");
                            Set<String> newUrls = new HashSet<>();
                            for (String line : lines) {
                                String trimmed = line.trim();
                                if (!TextUtils.isEmpty(trimmed) && trimmed.startsWith("http")) {
                                    newUrls.add(trimmed);
                                }
                            }

                            if (newUrls.isEmpty()) {
                                Utils.safeShowToast(activity, "请输入有效的API地址");
                                return;
                            }

                            DanmakuConfig config = DanmakuConfigManager.getConfig( activity);
                            config.setApiUrls(newUrls);
                            DanmakuConfigManager.saveConfig(activity, config);

                            Utils.safeShowToast(activity, "配置已保存");

                            DanmakuSpider.log("已保存API地址: " + newUrls);

                            dialog.dismiss();
                        }
                    });

                    clearBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                DanmakuSpider.clearCache(activity);
                                Utils.safeShowToast(activity, "缓存已清空");
                            } catch (Exception e) {
                                Utils.safeShowToast(activity, "清空失败");
                            }
                        }
                    });

                    lpConfigBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showLpConfigDialog(activity);
                            dialog.dismiss();
                        }
                    });

                    cancelBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                        }
                    });

                    safeShowDialog(activity, dialog);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static void showLpConfigDialog(Context ctx) {
        if (!(ctx instanceof Activity)) {
            DanmakuSpider.log("错误：Context不是Activity");
            return;
        }
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) {
            DanmakuSpider.log("Activity已销毁或正在销毁，不显示配置对话框");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    DanmakuSpider.log("Activity已销毁，不显示配置对话框");
                    return;
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setBackgroundColor(BACKGROUND_WHITE);
                mainLayout.setPadding(dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20));

                TextView title = new TextView(activity);
                title.setText("布局配置");
                title.setTextSize(24);
                title.setTextColor(PRIMARY_COLOR);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 20));
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                mainLayout.addView(title);

                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);

                // 宽度配置行
                LinearLayout widthLayout = new LinearLayout(activity);
                widthLayout.setOrientation(LinearLayout.HORIZONTAL);
                widthLayout.setGravity(Gravity.CENTER_VERTICAL);

                TextView widthLabel = new TextView(activity);
                widthLabel.setText("宽度:");
                widthLabel.setTextSize(14);
                widthLabel.setTextColor(TEXT_PRIMARY);
                widthLabel.setPadding(0, 0, dpToPx(activity, 10), 0);

                EditText widthInput = new EditText(activity);
                widthInput.setHint("0.1 - 1.0");
                widthInput.setText(String.valueOf(config.getLpWidth()));
                widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

                widthLayout.addView(widthLabel);
                widthLayout.addView(widthInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(widthLayout);

                // 高度配置行
                LinearLayout heightLayout = new LinearLayout(activity);
                heightLayout.setOrientation(LinearLayout.HORIZONTAL);
                heightLayout.setGravity(Gravity.CENTER_VERTICAL);

                TextView heightLabel = new TextView(activity);
                heightLabel.setText("高度:");
                heightLabel.setTextSize(14);
                heightLabel.setTextColor(TEXT_PRIMARY);
                heightLabel.setPadding(0, 0, dpToPx(activity, 10), 0);

                EditText heightInput = new EditText(activity);
                heightInput.setHint("0.1 - 1.0");
                heightInput.setText(String.valueOf(config.getLpHeight()));
                heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

                heightLayout.addView(heightLabel);
                heightLayout.addView(heightInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(heightLayout);

                // 透明度配置行
                LinearLayout alphaLayout = new LinearLayout(activity);
                alphaLayout.setOrientation(LinearLayout.HORIZONTAL);
                alphaLayout.setGravity(Gravity.CENTER_VERTICAL);

                TextView alphaLabel = new TextView(activity);
                alphaLabel.setText("透明度:");
                alphaLabel.setTextSize(14);
                alphaLabel.setTextColor(TEXT_PRIMARY);
                alphaLabel.setPadding(0, 0, dpToPx(activity, 10), 0);

                EditText alphaInput = new EditText(activity);
                alphaInput.setHint("0.1 - 1.0");
                alphaInput.setText(String.valueOf(config.getLpAlpha()));
                alphaInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

                alphaLayout.addView(alphaLabel);
                alphaLayout.addView(alphaInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(alphaLayout);


                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER);

                Button saveBtn = createStyledButton(activity, "保存", PRIMARY_COLOR);
                Button cancelBtn = createStyledButtonWithBorder(activity, "取消", PRIMARY_COLOR);

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 44), 1);
                btnParams.setMargins(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);

                saveBtn.setLayoutParams(btnParams);
                cancelBtn.setLayoutParams(btnParams);

                btnLayout.addView(saveBtn);
                btnLayout.addView(cancelBtn);

                mainLayout.addView(btnLayout);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();

                saveBtn.setOnClickListener(v -> {
                    try {
                        float width = Float.parseFloat(widthInput.getText().toString());
                        float height = Float.parseFloat(heightInput.getText().toString());
                        float alpha = Float.parseFloat(alphaInput.getText().toString());

                        if (width > 1.0f) width = 1.0f;
                        if (width < 0.1f) width = 0.1f;
                        if (height > 1.0f) height = 1.0f;
                        if (height < 0.1f) height = 0.1f;
                        if (alpha > 1.0f) alpha = 1.0f;
                        if (alpha < 0.1f) alpha = 0.1f;

                        config.setLpWidth(width);
                        config.setLpHeight(height);
                        config.setLpAlpha(alpha);
                        DanmakuConfigManager.saveConfig(activity, config);
                        Utils.safeShowToast(activity, "布局配置已保存");
                        dialog.dismiss();
                    } catch (NumberFormatException e) {
                        Utils.safeShowToast(activity, "请输入有效的数字");
                    }
                });

                cancelBtn.setOnClickListener(v -> dialog.dismiss());

                safeShowDialog(activity, dialog);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void showDanmakuStyleDialog(Context ctx) {
        if (!(ctx instanceof Activity)) {
            DanmakuSpider.log("错误：Context不是Activity");
            return;
        }
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) {
            DanmakuSpider.log("Activity已销毁或正在销毁，不显示配置对话框");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    DanmakuSpider.log("Activity已销毁，不显示配置对话框");
                    return;
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setBackgroundColor(BACKGROUND_WHITE);
                mainLayout.setPadding(dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20));

                TextView title = new TextView(activity);
                title.setText("弹幕UI风格");
                title.setTextSize(24);
                title.setTextColor(PRIMARY_COLOR);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 20));
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                mainLayout.addView(title);

                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                String[] styles = {"模板一", "模板二", "模板三", "模板四"};
                String currentStyle = config.getDanmakuStyle();
                
                AlertDialog dialog = builder.create();

                for (String style : styles) {
                    Button styleBtn = createStyledButton(activity, style, style.equals(currentStyle) ? PRIMARY_COLOR : GRAY_INACTIVE);
                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 44));
                    btnParams.setMargins(0, 0, 0, dpToPx(activity, 10));
                    styleBtn.setLayoutParams(btnParams);
                    mainLayout.addView(styleBtn);

                    styleBtn.setOnClickListener(v -> {
                        config.setDanmakuStyle(style);
                        DanmakuConfigManager.saveConfig(activity, config);
                        Utils.safeShowToast(activity, "弹幕UI风格已切换为: " + style);
                        dialog.dismiss();
                    });
                }

                builder.setView(mainLayout);
                dialog.setView(mainLayout);
                safeShowDialog(activity, dialog);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }


    // 创建带边框的按钮 - 电视端优化版本
    private static Button createStyledButtonWithBorder(Activity activity, String text, int color) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(color);
        button.setBackground(createTVFocusableBorderDrawable(color, false));
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        // 启用焦点支持电视端
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);

        // 添加电视端焦点效果 - 高对比度发光边框
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // 获得焦点时：发光边框 + 填充背景 + 白色文字
                    ((Button) v).setBackground(createTVFocusableBorderDrawable(TV_FOCUS_BORDER_COLOR, true));
                    ((Button) v).setTextColor(TV_FOCUS_TEXT_COLOR);
                } else {
                    // 失去焦点时恢复边框样式
                    ((Button) v).setBackground(createTVFocusableBorderDrawable(color, false));
                    ((Button) v).setTextColor(color);
                }
            }
        });

        return button;
    }

    // 创建实心按钮 - 电视端优化版本（带明显焦点高亮）
    private static Button createStyledButton(Activity activity, String text, int backgroundColor) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(createTVFocusableSolidDrawable(backgroundColor, false));
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        // 启用焦点支持电视端
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);

        // 添加电视端焦点效果 - 高对比度发光边框
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // 获得焦点时：加粗发光边框 + 高亮背景
                    ((Button) v).setBackground(createTVFocusableSolidDrawable(backgroundColor, true));
                    ((Button) v).setTextColor(TV_FOCUS_TEXT_COLOR);
                } else {
                    // 失去焦点时恢复原始样式
                    ((Button) v).setBackground(createTVFocusableSolidDrawable(backgroundColor, false));
                    ((Button) v).setTextColor(Color.WHITE);
                }
            }
        });

        return button;
    }

    // ========== 电视端焦点高亮专用Drawable方法 ==========

    /**
     * 创建电视端可聚焦的边框按钮背景
     * @param color 边框颜色
     * @param focused 是否为焦点状态
     */
    private static android.graphics.drawable.Drawable createTVFocusableBorderDrawable(int color, boolean focused) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        if (focused) {
            // 焦点状态：填充背景 + 加粗发光边框
            drawable.setColor(TV_FOCUS_BG_COLOR);
            drawable.setStroke(6, TV_FOCUS_BORDER_COLOR); // 6px加粗边框，高对比度
            drawable.setCornerRadius(18);
        } else {
            // 非焦点状态：透明背景 + 细边框
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(2, color);
            drawable.setCornerRadius(18);
        }
        return drawable;
    }

    /**
     * 创建电视端可聚焦的实心按钮背景
     * @param color 背景颜色
     * @param focused 是否为焦点状态
     */
    private static android.graphics.drawable.Drawable createTVFocusableSolidDrawable(int color, boolean focused) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(18);
        if (focused) {
            // 焦点状态：加粗发光边框，背景色稍微变亮
            drawable.setStroke(6, TV_FOCUS_BORDER_COLOR);
            // 背景色稍微提亮，增强对比度
            drawable.setColor(lightenColor(color, 0.15f));
        } else {
            drawable.setStroke(0, 0);
        }
        return drawable;
    }

    /**
     * 创建电视端可聚焦的透明按钮背景（用于深色主题）
     * @param color 背景颜色（带透明度）
     * @param focused 是否为焦点状态
     */
    private static android.graphics.drawable.Drawable createTVFocusableTransparentDrawable(int color, boolean focused) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(18);
        if (focused) {
            // 焦点状态：加粗发光边框
            drawable.setStroke(6, TV_FOCUS_BORDER_COLOR);
            // 背景色稍微增加不透明度，增强对比度
            drawable.setColor(lightenAlpha(color, 0.2f));
        } else {
            drawable.setStroke(0, 0);
        }
        return drawable;
    }

    /**
     * 增加颜色透明度（让透明颜色更不透明一点）
     */
    private static int lightenAlpha(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        // 增加alpha值（减少透明度）
        int newA = Math.min(255, (int) (a + (255 - a) * factor));
        return (newA << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 创建电视端输入框背景
     * @param focused 是否为焦点状态
     * @param isDarkTheme 是否为深色主题
     */
    private static android.graphics.drawable.Drawable createTVInputDrawable(boolean focused, boolean isDarkTheme) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        if (focused) {
            // 焦点状态：明显的浅蓝背景 + 蓝色边框
            drawable.setColor(isDarkTheme ? 0x44007AFF : TV_INPUT_FOCUS_BG);
            drawable.setStroke(4, TV_INPUT_FOCUS_BORDER);
            drawable.setCornerRadius(12);
        } else {
            // 非焦点状态：普通背景
            drawable.setColor(isDarkTheme ? DARK_BG_SECONDARY : BACKGROUND_LIGHT);
            drawable.setStroke(2, isDarkTheme ? DARK_BORDER : BORDER_COLOR);
            drawable.setCornerRadius(12);
        }
        return drawable;
    }

    /**
     * 为输入框添加电视端焦点效果
     */
    private static void applyTVInputFocusEffect(EditText editText, boolean isDarkTheme) {
        editText.setFocusable(true);
        editText.setFocusableInTouchMode(true);
        editText.setBackground(createTVInputDrawable(false, isDarkTheme));

        editText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ((EditText) v).setBackground(createTVInputDrawable(hasFocus, isDarkTheme));
                if (hasFocus) {
                    // 焦点时文字颜色加深，提示用户正在编辑
                    ((EditText) v).setTextColor(isDarkTheme ? DARK_TEXT_PRIMARY : TEXT_PRIMARY);
                }
            }
        });
    }

    // 颜色提亮辅助方法
    private static int lightenColor(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) Math.min(255, ((color >> 16) & 0xFF) + (255 - ((color >> 16) & 0xFF)) * factor);
        int g = (int) Math.min(255, ((color >> 8) & 0xFF) + (255 - ((color >> 8) & 0xFF)) * factor);
        int b = (int) Math.min(255, (color & 0xFF) + (255 - (color & 0xFF)) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // 创建圆角背景 - 带阴影效果
    private static android.graphics.drawable.Drawable createRoundedBackgroundDrawable(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(18);
        drawable.setStroke(0, 0); // 无边框
        return drawable;
    }

    // 创建带边框的圆角背景
    private static android.graphics.drawable.Drawable createRoundedBorderDrawable(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(2, color);
        drawable.setCornerRadius(18);
        return drawable;
    }

    // 创建带粗边框的焦点页签背景（用于电视端焦点高亮）
    private static android.graphics.drawable.Drawable createFocusedTabDrawable(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setStroke(4, PRIMARY_COLOR); // 加粗外边框
        drawable.setCornerRadius(18);
        return drawable;
    }

    // 创建带边框的填色圆角背景（浅色主题）
    private static android.graphics.drawable.Drawable createBorderDrawable(int fillColor, int strokeColor, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(2, strokeColor);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    // 创建带边框的填色圆角背景（深色主题）
    private static android.graphics.drawable.Drawable createDarkBorderDrawable(int fillColor, int strokeColor, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(2, strokeColor);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    // 安全显示对话框的辅助方法
    private static void safeShowDialog(Activity activity, AlertDialog dialog) {
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed() && !dialog.isShowing()) {
            try {
                dialog.show();
            } catch (Exception e) {
                DanmakuSpider.log("显示对话框失败: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            DanmakuSpider.log("Activity已销毁或对话框已在显示，无法显示对话框");
        }
    }


    /**
     * 显示带标签页切换的日志对话框（弹幕日志 + Go代理日志）
     * @param ctx 上下文
     */
    public static void showUnifiedLogDialog(Context ctx) {
        if (!(ctx instanceof Activity)) {
            DanmakuSpider.log("错误：Context不是Activity");
            return;
        }
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) {
            DanmakuSpider.log("Activity已销毁或正在销毁，不显示日志对话框");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    DanmakuSpider.log("Activity已销毁，不显示日志对话框");
                    return;
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setBackgroundColor(BACKGROUND_WHITE);
                mainLayout.setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 12));

                // 标题
                TextView title = new TextView(activity);
                title.setText("日志查看器");
                title.setTextSize(20);
                title.setTextColor(PRIMARY_COLOR);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 8));
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                mainLayout.addView(title);

                // 判断是否有Go代理
                final boolean hasGoProxy = GoProxyManager.isGoProxyAssetExists();

                // 当前选中的页签索引（0=弹幕日志，1=Go代理日志）
                final int[] currentTab = {0};
                // 当前排序状态（false=正序，true=倒序）
                final boolean[] isReversed = {false};

                // 日志内容显示区域 - 使用权重自适应高度
                ScrollView scrollView = new ScrollView(activity);
                scrollView.setBackgroundColor(0xFFF5F5F5);
                LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
                scrollView.setLayoutParams(scrollParams);
                scrollView.setPadding(dpToPx(activity, 10), dpToPx(activity, 10), dpToPx(activity, 10), dpToPx(activity, 10));

                TextView logText = new TextView(activity);
                logText.setTextSize(11);
                logText.setTextColor(TEXT_PRIMARY);
                logText.setTypeface(android.graphics.Typeface.MONOSPACE);
                logText.setText(DanmakuSpider.getLogContent());
                scrollView.addView(logText);
                mainLayout.addView(scrollView);

                // 页签容器 - 放在日志区域下方
                LinearLayout tabContainer = new LinearLayout(activity);
                tabContainer.setOrientation(LinearLayout.HORIZONTAL);
                tabContainer.setGravity(Gravity.CENTER);
                tabContainer.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 8));

                // 弹幕日志页签按钮
                Button danmakuTabBtn = createTabButton(activity, "弹幕日志", true);
                LinearLayout.LayoutParams danmakuTabParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 36), 1);
                danmakuTabParams.setMargins(0, 0, dpToPx(activity, 4), 0);
                danmakuTabBtn.setLayoutParams(danmakuTabParams);

                // Go代理日志页签按钮（仅当存在时显示）
                Button goProxyTabBtn = null;
                if (hasGoProxy) {
                    goProxyTabBtn = createTabButton(activity, "Go代理日志", false);
                    LinearLayout.LayoutParams goProxyTabParams = new LinearLayout.LayoutParams(
                            0, dpToPx(activity, 36), 1);
                    goProxyTabParams.setMargins(dpToPx(activity, 4), 0, 0, 0);
                    goProxyTabBtn.setLayoutParams(goProxyTabParams);
                }

                tabContainer.addView(danmakuTabBtn);
                if (goProxyTabBtn != null) {
                    tabContainer.addView(goProxyTabBtn);
                }
                mainLayout.addView(tabContainer);

                // 按钮区域
                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER);
                btnLayout.setPadding(0, dpToPx(activity, 4), 0, 0);

                Button sortButton = createStyledButtonWithBorder(activity, "排序: 正序", PRIMARY_COLOR);
                Button copyButton = createStyledButtonWithBorder(activity, "复制", PRIMARY_COLOR);
                Button clearButton = createStyledButtonWithBorder(activity, "清空", PRIMARY_COLOR);
                Button closeButton = createStyledButtonWithBorder(activity, "关闭", PRIMARY_COLOR);

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 40), 1);
                btnParams.setMargins(dpToPx(activity, 4), 0, dpToPx(activity, 4), 0);

                sortButton.setLayoutParams(btnParams);
                copyButton.setLayoutParams(btnParams);
                clearButton.setLayoutParams(btnParams);
                closeButton.setLayoutParams(btnParams);

                copyButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String logContent = DanmakuSpider.getLogContent();
                        if (!TextUtils.isEmpty(logContent)) {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            android.content.ClipData clip = android.content.ClipData.newPlainText("Leo日志", logContent);
                            clipboard.setPrimaryClip(clip);
                            Utils.safeShowToast(activity, "日志已复制到剪贴板");
                        } else {
                            Utils.safeShowToast(activity, "日志为空");
                        }
                    }
                });

                btnLayout.addView(sortButton);
                btnLayout.addView(copyButton);
                btnLayout.addView(clearButton);
                btnLayout.addView(closeButton);
                mainLayout.addView(btnLayout);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();

                // 设置对话框最大高度为屏幕高度的70%
                dialog.setOnShowListener(d -> {
                    android.view.Window window = dialog.getWindow();
                    if (window != null) {
                        android.view.WindowManager.LayoutParams params = window.getAttributes();
                        params.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90);
                        params.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.85);
                        window.setAttributes(params);
                    }
                });

                // 页签切换逻辑
                final Button finalGoProxyTabBtn = goProxyTabBtn;
                danmakuTabBtn.setOnClickListener(v -> {
                    currentTab[0] = 0;
                    updateTabButtonState(danmakuTabBtn, true);
                    if (finalGoProxyTabBtn != null) {
                        updateTabButtonState(finalGoProxyTabBtn, false);
                    }
                    // 弹幕日志支持倒序
                    logText.setText(DanmakuSpider.getLogContent(isReversed[0]));
                    // 倒序时滚动到顶部，正序时滚动到底部
                    scrollView.post(() -> scrollView.fullScroll(isReversed[0] ? ScrollView.FOCUS_UP : ScrollView.FOCUS_DOWN));
                });

                if (goProxyTabBtn != null) {
                    final Button finalGoProxyTabBtnForClick = goProxyTabBtn;
                    goProxyTabBtn.setOnClickListener(v -> {
                        currentTab[0] = 1;
                        updateTabButtonState(danmakuTabBtn, false);
                        updateTabButtonState(finalGoProxyTabBtnForClick, true);
                        // Go代理日志支持倒序
                        logText.setText(GoProxyManager.getLogContent(isReversed[0]));
                        // 倒序时滚动到顶部，正序时滚动到底部
                        scrollView.post(() -> scrollView.fullScroll(isReversed[0] ? ScrollView.FOCUS_UP : ScrollView.FOCUS_DOWN));
                    });
                }

                // 排序按钮逻辑（弹幕日志和Go代理日志都支持倒序）
                sortButton.setOnClickListener(v -> {
                    isReversed[0] = !isReversed[0];
                    sortButton.setText(isReversed[0] ? "排序: 倒序" : "排序: 正序");

                    if (currentTab[0] == 0) {
                        // 弹幕日志支持倒序
                        logText.setText(DanmakuSpider.getLogContent(isReversed[0]));
                    } else {
                        // Go代理日志支持倒序
                        logText.setText(GoProxyManager.getLogContent(isReversed[0]));
                    }
                    // 倒序时滚动到顶部，正序时滚动到底部
                    scrollView.post(() -> scrollView.fullScroll(isReversed[0] ? ScrollView.FOCUS_UP : ScrollView.FOCUS_DOWN));
                });

                // 清空按钮逻辑
                clearButton.setOnClickListener(v -> {
                    if (currentTab[0] == 0) {
                        DanmakuSpider.clearLogs();
                        logText.setText(DanmakuSpider.getLogContent(isReversed[0]));
                    } else {
                        GoProxyManager.clearLogs();
                        logText.setText(GoProxyManager.getLogContent(isReversed[0]));
                    }
                });

                closeButton.setOnClickListener(v -> dialog.dismiss());

                safeShowDialog(activity, dialog);

                // 初始滚动到底部（正序时）
                scrollView.post(() -> scrollView.fullScroll(isReversed[0] ? ScrollView.FOCUS_UP : ScrollView.FOCUS_DOWN));

            } catch (Exception e) {
                DanmakuSpider.log("显示统一日志对话框异常: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // 创建页签按钮
    private static Button createTabButton(Activity activity, String text, boolean isActive) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        // 设置焦点可用，支持电视端遥控器操作
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        updateTabButtonState(button, isActive);

        // 添加焦点变化监听，电视端高亮效果
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // 获得焦点时显示高亮边框 - 使用加粗边框而不是缩放，避免覆盖其他按钮
                ((Button) v).setBackground(createFocusedTabDrawable(PRIMARY_LIGHT));
                ((Button) v).setTextColor(Color.WHITE);
            } else {
                // 失去焦点时恢复原始状态
                // 需要根据当前激活状态恢复样式
                boolean isCurrentlyActive = v.getTag() != null && (Boolean) v.getTag();
                updateTabButtonState((Button) v, isCurrentlyActive);
            }
        });

        // 保存激活状态到tag，用于焦点恢复时判断
        button.setTag(isActive);

        return button;
    }

    // 更新页签按钮状态
    private static void updateTabButtonState(Button button, boolean isActive) {
        if (button == null) return;
        if (isActive) {
            button.setTextColor(Color.WHITE);
            button.setBackground(createRoundedBackgroundDrawable(PRIMARY_COLOR));
        } else {
            button.setTextColor(PRIMARY_COLOR);
            button.setBackground(createRoundedBorderDrawable(PRIMARY_COLOR));
        }
        // 更新tag保存当前激活状态，用于焦点恢复时判断
        button.setTag(isActive);
    }


    // 显示搜索对话框
    public static void showSearchDialog(Activity activity, EpisodeInfo episodeInfo) {
        // 检查Activity状态
        if (activity.isFinishing() || activity.isDestroyed()) {
            DanmakuSpider.log("Activity已销毁或正在销毁，不显示搜索对话框");
            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 在创建对话框前再次检查状态
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        DanmakuSpider.log("Activity已销毁，不显示搜索对话框");
                        return;
                    }

                    DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                    String style = config.getDanmakuStyle();
                    boolean isDarkStyle = style.equals("模板三") || style.equals("模板四");

                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    LinearLayout mainLayout = new LinearLayout(activity);
                    mainLayout.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable mainBg = new android.graphics.drawable.GradientDrawable();
                    int dialogColor;
                    if (style.equals("模板二")) {
                        dialogColor = 0xFFF2F2F7;
                    } else if (isDarkStyle) {
                        dialogColor = DARK_BG_SYSTEM;
                    } else {
                        dialogColor = BACKGROUND_WHITE;
                    }
                    mainBg.setColor(dialogColor);
                    mainBg.setCornerRadius(dpToPx(activity, 18));
                    mainLayout.setBackground(mainBg);
                    mainLayout.setPadding(dpToPx(activity, 15), dpToPx(activity, 10), dpToPx(activity, 15), dpToPx(activity, 10));

                    LinearLayout searchLayout = new LinearLayout(activity);
                    searchLayout.setOrientation(LinearLayout.HORIZONTAL);
                    searchLayout.setPadding(0, 0, 0, dpToPx(activity, 4));
                    searchLayout.setGravity(Gravity.CENTER_VERTICAL);

                    final EditText searchInput = new EditText(activity);
                    searchInput.setHint("输入关键词搜索弹幕...");
                    String initialKeyword = episodeInfo != null && !TextUtils.isEmpty(episodeInfo.getOriginalTitle()) 
                            ? episodeInfo.getOriginalTitle() 
                            : (episodeInfo != null && episodeInfo.getEpisodeNames() != null && !episodeInfo.getEpisodeNames().isEmpty() 
                                ? episodeInfo.getEpisodeNames().get(0) : "");
                    String cachedKeyword = SharedPreferencesService.getSearchKeywordCache(activity, initialKeyword);
                    searchInput.setText(cachedKeyword);
                    searchInput.setHintTextColor(isDarkStyle ? DARK_TEXT_TERTIARY : TEXT_TERTIARY);
                    android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
                    inputBg.setColor(isDarkStyle ? 0x44CCCCCC : 0xFFE0E0E0);
                    inputBg.setCornerRadius(dpToPx(activity, 12));
                    searchInput.setBackground(inputBg);
                    searchInput.setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10));
                    searchInput.setTextSize(14);
                    searchInput.setTextColor(isDarkStyle ? DARK_TEXT_PRIMARY : TEXT_PRIMARY);
                    searchInput.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dpToPx(activity, 44), 1);
                    inputParams.setMargins(dpToPx(activity, 8), 0, dpToPx(activity, 8), 0);
                    searchInput.setLayoutParams(inputParams);

                    int reverseColor, reverseActiveColor;
                    if (style.equals("模板二")) {
                        reverseColor = REVERSE_GRAY;
                        reverseActiveColor = ACCENT_T2;
                    } else if (style.equals("模板三")) {
                        reverseColor = REVERSE_GRAY;
                        reverseActiveColor = ACCENT_T3;
                    } else if (style.equals("模板四")) {
                        reverseColor = REVERSE_GRAY;
                        reverseActiveColor = ACCENT_T4;
                    } else {
                        reverseColor = REVERSE_GRAY;
                        reverseActiveColor = PRIMARY_COLOR;
                    }
                    Button reverseBtn = isDarkStyle ?
                            createDarkSolidButton(activity, isReversed ? "倒序" : "正序", isReversed ? reverseActiveColor : reverseColor) :
                            createStyledButton(activity, isReversed ? "倒序" : "正序", isReversed ? reverseActiveColor : reverseColor);
                    reverseBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(activity, 56), dpToPx(activity, 44)));
                    reverseBtn.setTextSize(14);

                    int searchColor;
                    if (style.equals("模板二")) {
                        searchColor = ACCENT_T2;
                    } else if (style.equals("模板三")) {
                        searchColor = ACCENT_T3;
                    } else if (style.equals("模板四")) {
                        searchColor = ACCENT_T4;
                    } else {
                        searchColor = PRIMARY_COLOR;
                    }
                    Button searchBtn = isDarkStyle ?
                            createDarkSolidButton(activity, "搜索", searchColor) :
                            createStyledButton(activity, "搜索", searchColor);
                    searchBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(activity, 80), dpToPx(activity, 44)));
                    searchBtn.setTextSize(17);

                    TextView gearBtn = new TextView(activity);
                    gearBtn.setText("⚙️");
                    gearBtn.setTextSize(15);
                    gearBtn.setTextColor(isDarkStyle ? DARK_TEXT_PRIMARY : TEXT_PRIMARY);
                    gearBtn.setGravity(Gravity.CENTER);
                    gearBtn.setClickable(true);
                    gearBtn.setFocusable(true);
                    LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(dpToPx(activity, 29), dpToPx(activity, 44));
                    gearParams.setMargins(0, 0, dpToPx(activity, 8), 0);
                    gearBtn.setLayoutParams(gearParams);
                    gearBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            DanmakuScanner.showLeoButtonMenu(activity);
                        }
                    });
                    searchLayout.addView(gearBtn);
                    searchLayout.addView(searchInput);
                    searchLayout.addView(searchBtn);
                    View separator = new View(activity);
                    LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(dpToPx(activity, 16), dpToPx(activity, 44));
                    separator.setLayoutParams(separatorParams);
                    searchLayout.addView(separator);
                    searchLayout.addView(reverseBtn);
                    mainLayout.addView(searchLayout);

                    LinearLayout tabContainer = new LinearLayout(activity);
                    tabContainer.setOrientation(LinearLayout.HORIZONTAL);
                    tabContainer.setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 8));
                    int tabBgColor;
                    if (style.equals("模板二")) {
                        tabBgColor = BACKGROUND_WHITE;
                    } else if (style.equals("模板一")) {
                        tabBgColor = TAB_BG_T1;
                    } else if (isDarkStyle) {
                        tabBgColor = DARK_TAB_BG;
                    } else {
                        tabBgColor = BACKGROUND_LIGHT;
                    }
                    tabContainer.setBackgroundColor(tabBgColor);
                    LinearLayout.LayoutParams tabContainerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 48));
                    tabContainerParams.setMargins(0, dpToPx(activity, 2), 0, dpToPx(activity, 8));
                    tabContainer.setLayoutParams(tabContainerParams);
                    mainLayout.addView(tabContainer);

                    ScrollView resultScroll = new ScrollView(activity);
                    resultScroll.setBackgroundColor(isDarkStyle ? Color.TRANSPARENT : BACKGROUND_WHITE);
                    LinearLayout resultContainer = new LinearLayout(activity);
                    resultContainer.setOrientation(LinearLayout.VERTICAL);
                    resultContainer.setGravity(Gravity.CENTER_HORIZONTAL);
                    resultContainer.setPadding(0, 10, 0, 0);
                    resultContainer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

                    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
                    scrollParams.weight = 1;
                    resultScroll.setLayoutParams(scrollParams);

                    resultScroll.addView(resultContainer);
                    mainLayout.addView(resultScroll);

                    builder.setView(mainLayout);
                    final AlertDialog dialog = builder.create();

                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

                    final int finalReverseActiveColor = reverseActiveColor;
                    reverseBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            isReversed = !isReversed;
                            if (isDarkStyle) {
                                reverseBtn.setBackground(createRoundedTransparentDrawable(isReversed ? finalReverseActiveColor : reverseColor));
                            } else {
                                reverseBtn.setBackground(createRoundedBackgroundDrawable(isReversed ? finalReverseActiveColor : reverseColor));
                            }
                            reverseBtn.setText(isReversed ? "倒序" : "正序");
                            showResultsForTab(resultContainer, currentItems, activity, dialog);
                        }
                    });

                    searchBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String keyword = searchInput.getText().toString().trim();
                            if (TextUtils.isEmpty(keyword)) {
                                Utils.safeShowToast(activity, "请输入关键词");
                                return;
                            }

                            String cacheKey = episodeInfo != null && !TextUtils.isEmpty(episodeInfo.getOriginalTitle()) 
                                    ? episodeInfo.getOriginalTitle() : "";
                            if (!keyword.equals(cacheKey)) {
                                SharedPreferencesService.saveSearchKeywordCache(activity, cacheKey, keyword);
                                DanmakuSpider.log("已保存新的搜索缓存: " + cacheKey + " -> " + keyword);
                            } else {
                                SharedPreferencesService.saveSearchKeywordCache(activity, cacheKey, "");
                                DanmakuSpider.log("已清空搜索缓存: " + cacheKey);
                            }

                            resultContainer.removeAllViews();
                            tabContainer.removeAllViews();
                            TextView loading = new TextView(activity);
                            loading.setText("正在搜索: " + keyword);
                            loading.setGravity(Gravity.CENTER);
                            loading.setPadding(0, 20, 0, 20);
                            loading.setTextColor(isDarkStyle ? DARK_TEXT_SECONDARY : TEXT_SECONDARY);
                            resultContainer.addView(loading);

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    // 创建新的 EpisodeInfo 用于手动搜索（使用用户输入的关键词）
                                    EpisodeInfo searchEpisodeInfo = new EpisodeInfo();
                                    List<String> names = new ArrayList<>();
                                    names.add(keyword);
                                    searchEpisodeInfo.setEpisodeNames(names);
                                    // 如果原 episodeInfo 有集数信息，保留它
                                    if (episodeInfo != null && !TextUtils.isEmpty(episodeInfo.getEpisodeNum())) {
                                        searchEpisodeInfo.setEpisodeNum(episodeInfo.getEpisodeNum());
                                    }
                                    List<DanmakuItem> results = LeoDanmakuService.manualSearch(searchEpisodeInfo, activity);
                                    if (isReversed) {
                                        java.util.Collections.reverse(results);
                                    }

                                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                                        @Override
                                        public void run() {
                                            resultContainer.removeAllViews();
                                            tabContainer.removeAllViews();

                                            if (results.isEmpty()) {
                                                TextView empty = new TextView(activity);
                                                empty.setText("未找到结果");
                                                empty.setGravity(Gravity.CENTER);
                                                empty.setPadding(0, 50, 0, 50);
                                                empty.setTextColor(isDarkStyle ? DARK_TEXT_SECONDARY : TEXT_SECONDARY);
                                                resultContainer.addView(empty);
                                                return;
                                            }

                                            java.util.Map<String, List<DanmakuItem>> groupedResults = new java.util.HashMap<>();
                                            for (DanmakuItem item : results) {
                                                String from = item.from != null ? item.from : "默认";
                                                if (!groupedResults.containsKey(from)) {
                                                    groupedResults.put(from, new java.util.ArrayList<>());
                                                }
                                                groupedResults.get(from).add(item);
                                            }

                                            java.util.List<String> tabs = new java.util.ArrayList<>(groupedResults.keySet());
                                            java.util.Collections.sort(tabs);

                                            for (int i = 0; i < tabs.size(); i++) {
                                                String tabName = tabs.get(i);
                                                int tabAccent = getTemplateAccent(style);
                                                Button tabBtn = new Button(activity);
                                                tabBtn.setText(tabName);
                                                tabBtn.setPadding(15, 10, 15, 10);
                                                tabBtn.setTextSize(14);
                                                tabBtn.setTypeface(null, android.graphics.Typeface.BOLD);

                                                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                                                tabParams.setMargins(5, 0, 5, 0);
                                                tabBtn.setLayoutParams(tabParams);

                                                final int tabIndex = i;
                                                final int finalTabAccent = tabAccent;
                                                tabBtn.setTag(new Object[]{tabName, finalTabAccent});

                                                tabBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                                                    @Override
                                                    public void onFocusChange(View v, boolean hasFocus) {
                                                        Button btn = (Button) v;
                                                        int restingColor = (int) ((Object[]) btn.getTag())[1];
                                                        if (hasFocus) {
                                                            if (isDarkStyle) {
                                                                android.graphics.drawable.GradientDrawable focusDrawable = new android.graphics.drawable.GradientDrawable();
                                                                focusDrawable.setColor(lightenColor(restingColor, 0.4f));
                                                                focusDrawable.setStroke(4, 0xFF00D4FF);
                                                                focusDrawable.setCornerRadius(18);
                                                                v.setBackground(focusDrawable);
                                                                btn.setTextColor(DARK_TEXT_PRIMARY);
                                                            } else {
                                                                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                                                                gd.setColor(restingColor);
                                                                gd.setStroke(4, finalTabAccent);
                                                                gd.setCornerRadius(18);
                                                                v.setBackground(gd);
                                                                btn.setTextColor(Color.WHITE);
                                                            }
                                                            v.setScaleX(1.04f);
                                                            v.setScaleY(1.04f);
                                                        } else {
                                                            if (isDarkStyle) {
                                                                v.setBackground(createRoundedTransparentDrawable(restingColor));
                                                                btn.setTextColor(DARK_TEXT_PRIMARY);
                                                            } else {
                                                                v.setBackground(createRoundedBackgroundDrawable(restingColor));
                                                                btn.setTextColor(Color.WHITE);
                                                            }
                                                            v.setScaleX(1.0f);
                                                            v.setScaleY(1.0f);
                                                        }
                                                    }
                                                });

                                                tabBtn.setOnClickListener(new View.OnClickListener() {
                                                    @Override
                                                    public void onClick(View v1) {
                                                        for (int j = 0; j < tabContainer.getChildCount(); j++) {
                                                            Button btn = (Button) tabContainer.getChildAt(j);
                                                            Object[] tag = (Object[]) btn.getTag();
                                                            if (j == tabIndex) {
                                                                if (isDarkStyle) {
                                                                    btn.setBackground(createRoundedTransparentDrawable(finalTabAccent));
                                                                    btn.setTextColor(DARK_TEXT_PRIMARY);
                                                                } else {
                                                                    btn.setBackground(createRoundedBackgroundDrawable(finalTabAccent));
                                                                    btn.setTextColor(Color.WHITE);
                                                                }
                                                                tag[1] = finalTabAccent;
                                                            } else {
                                                                int inactive = isDarkStyle ? DARK_INACTIVE : GRAY_INACTIVE;
                                                                if (isDarkStyle) {
                                                                    btn.setBackground(createRoundedTransparentDrawable(inactive));
                                                                    btn.setTextColor(DARK_TEXT_PRIMARY);
                                                                } else {
                                                                    btn.setBackground(createRoundedBackgroundDrawable(inactive));
                                                                    btn.setTextColor(Color.WHITE);
                                                                }
                                                                tag[1] = inactive;
                                                            }
                                                        }
                                                        showResultsForTab(resultContainer, groupedResults.get(tabName), activity, dialog);
                                                    }
                                                });

                                                tabContainer.addView(tabBtn);

                                                List<DanmakuItem> tabItems = groupedResults.get(tabName);
                                                boolean containsLastUrl = false;
                                                if (DanmakuManager.lastDanmakuUrl != null && !DanmakuManager.lastDanmakuUrl.isEmpty()) {
                                                    for (DanmakuItem item : tabItems) {
                                                        if (item.getDanmakuUrl() != null && item.getDanmakuUrl().equals(DanmakuManager.lastDanmakuUrl)) {
                                                            containsLastUrl = true;
                                                            break;
                                                        }
                                                    }
                                                }

                                                int initialColor;
                                                if (DanmakuManager.lastDanmakuUrl == null || DanmakuManager.lastDanmakuUrl.isEmpty()) {
                                                    initialColor = (i == 0) ? finalTabAccent : (isDarkStyle ? DARK_INACTIVE : GRAY_INACTIVE);
                                                } else {
                                                    initialColor = containsLastUrl ? finalTabAccent : (isDarkStyle ? DARK_INACTIVE : GRAY_INACTIVE);
                                                }
                                                if (isDarkStyle) {
                                                    tabBtn.setBackground(createRoundedTransparentDrawable(initialColor));
                                                    tabBtn.setTextColor(DARK_TEXT_PRIMARY);
                                                } else {
                                                    tabBtn.setBackground(createRoundedBackgroundDrawable(initialColor));
                                                    tabBtn.setTextColor(Color.WHITE);
                                                }
                                                ((Object[]) tabBtn.getTag())[1] = initialColor;
                                                if (initialColor == finalTabAccent) {
                                                    showResultsForTab(resultContainer, groupedResults.get(tabName), activity, dialog);
                                                }
                                            }
                                        }
                                    });
                                }
                            }).start();
                        }
                    });

                    safeShowDialog(activity, dialog);

                    android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                    lp.copyFrom(dialog.getWindow().getAttributes());
                    lp.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * config.getLpWidth());
                    lp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * config.getLpHeight());
                    lp.alpha = config.getLpAlpha();
                    dialog.getWindow().setAttributes(lp);

                    String keywordToSearch = SharedPreferencesService.getSearchKeywordCache(activity, initialKeyword);
                    if (!TextUtils.isEmpty(keywordToSearch)) {
                        searchBtn.performClick();
                    }
                
                } catch (Exception e) {
                    DanmakuSpider.log("显示搜索对话框异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    // dp转px
    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }


    // 为指定页签显示结果
    private static void showResultsForTab(LinearLayout resultContainer, List<DanmakuItem> items,
                                          Activity activity, AlertDialog dialog) {
        resultContainer.removeAllViews();
        currentItems = items;

        if (items == null || items.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("该来源下无结果");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 20, 0, 20);
            resultContainer.addView(empty);
            return;
        }

        // 按 animeTitle 分组
        java.util.Map<String, List<DanmakuItem>> animeGroups = new java.util.HashMap<>();
        for (DanmakuItem item : items) {
            String animeTitle = item.animeTitle != null ? item.animeTitle : item.title;
            if (!animeGroups.containsKey(animeTitle)) {
                animeGroups.put(animeTitle, new java.util.ArrayList<>());
            }
            animeGroups.get(animeTitle).add(item);
        }

        // 检查哪些分组包含上次使用的弹幕URL
        java.util.Set<String> groupsWithLastUrl = new java.util.HashSet<>();
        if (DanmakuManager.lastDanmakuUrl != null) {
            for (java.util.Map.Entry<String, List<DanmakuItem>> entry : animeGroups.entrySet()) {
                String animeTitle = entry.getKey();
                List<DanmakuItem> animeItems = entry.getValue();
                for (DanmakuItem item : animeItems) {
                    if (item.getDanmakuUrl() != null && item.getDanmakuUrl().equals(DanmakuManager.lastDanmakuUrl)) {
                        groupsWithLastUrl.add(animeTitle);
                        break;
                    }
                }
            }
        }

        // 用于跟踪当前选中的分组按钮
        final java.util.Map<String, Button> groupButtons = new java.util.HashMap<>();
        java.util.List<String> animeTitles = new java.util.ArrayList<>(animeGroups.keySet());
        java.util.Collections.sort(animeTitles);

        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        String currentStyle = config.getDanmakuStyle();
        boolean isDarkStyle = currentStyle.equals("模板三") || currentStyle.equals("模板四");

        boolean useGrid = currentStyle.equals("模板二") || currentStyle.equals("模板三");

        // 模板专用分组按钮色
        int grpSel, grpFoc;
        final int grpSelLight, grpFocLight;
        if (currentStyle.equals("模板三")) {
            grpSel = 0xAA35C958;
            grpFoc = 0xCC35C958;
            grpSelLight = 0; grpFocLight = 0;
        } else if (currentStyle.equals("模板四")) {
            grpSel = 0xAA0A84FF;
            grpFoc = 0xCC0A84FF;
            grpSelLight = 0; grpFocLight = 0;
        } else if (currentStyle.equals("模板二")) {
            grpSel = 0xAAFF9F0A;
            grpFoc = 0xCCFF9F0A;
            grpSelLight = ACCENT_T2;
            grpFocLight = SECONDARY_DARK;
        } else {
            grpSel = DARK_PRIMARY_COLOR;
            grpFoc = DARK_PRIMARY_DARK;
            grpSelLight = PRIMARY_COLOR;
            grpFocLight = PRIMARY_DARK;
        }

        if (useGrid) {
            // 使用网格布局
            for (int groupIndex = 0; groupIndex < animeTitles.size(); groupIndex++) {
                String animeTitle = animeTitles.get(groupIndex);
                List<DanmakuItem> animeItems = animeGroups.get(animeTitle);

                // 创建分组按钮
                Button groupBtn = new Button(activity);
                groupBtn.setText(animeTitle + " (" + animeItems.size() + "集)");
                groupBtn.setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 12));
                groupBtn.setTextSize(14);
                groupBtn.setTypeface(null, android.graphics.Typeface.BOLD);

                if (isDarkStyle) {
                    groupBtn.setTextColor(DARK_TEXT_PRIMARY);
                    if (groupsWithLastUrl.contains(animeTitle)) {
                        groupBtn.setBackground(createRoundedTransparentDrawable(grpSel));
                    } else {
                        groupBtn.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                    }
                } else {
                    if (groupsWithLastUrl.contains(animeTitle)) {
                        groupBtn.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                        groupBtn.setTextColor(Color.WHITE);
                    } else {
                        groupBtn.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                        groupBtn.setTextColor(TEXT_PRIMARY);
                    }
                }

                groupBtn.setClickable(true);
                groupBtn.setFocusable(true);

                // 保存按钮引用，用于管理选中状态
                groupButtons.put(animeTitle, groupBtn);

                // 添加焦点效果
                groupBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        Button button = (Button) v;
                        String title = null;

                        // 找到对应的标题
                        for (java.util.Map.Entry<String, Button> entry : groupButtons.entrySet()) {
                            if (entry.getValue() == v) {
                                title = entry.getKey();
                                break;
                            }
                        }

                        if (hasFocus) {
                            if (isDarkStyle) {
                                v.setBackground(createRoundedTransparentDrawable(grpFoc));
                                button.setTextColor(DARK_TEXT_PRIMARY);
                            } else {
                                v.setBackground(createRoundedBackgroundDrawable(grpFocLight));
                                button.setTextColor(Color.WHITE);
                            }
                            v.setScaleX(1.04f);
                            v.setScaleY(1.04f);
                        } else {
                            // 失去焦点时，恢复到原始选中状态颜色
                            if (groupsWithLastUrl.contains(title)) {
                                if (isDarkStyle) {
                                    v.setBackground(createRoundedTransparentDrawable(grpSel));
                                    button.setTextColor(DARK_TEXT_PRIMARY);
                                } else {
                                    v.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                                    button.setTextColor(Color.WHITE);
                                }
                            } else {
                                if (isDarkStyle) {
                                    v.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                                    button.setTextColor(DARK_TEXT_PRIMARY);
                                } else {
                                    v.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                                    button.setTextColor(TEXT_PRIMARY);
                                }
                            }
                            v.setScaleX(1.0f);
                            v.setScaleY(1.0f);
                        }
                    }
                });

                // 添加展开/收起状态标记和网格容器的引用
                Object[] stateInfo = new Object[]{0, 0, null}; // [isExpanded(0/1), childCount, gridContainer]
                groupBtn.setTag(stateInfo);

                // 点击分组按钮展开/收起内容
                groupBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 点击时更新选中状态
                        for (java.util.Map.Entry<String, Button> entry : groupButtons.entrySet()) {
                            Button otherBtn = entry.getValue();
                            if (otherBtn == v) {
                                if (isDarkStyle) {
                                    otherBtn.setBackground(createRoundedTransparentDrawable(grpSel));
                                    otherBtn.setTextColor(DARK_TEXT_PRIMARY);
                                } else {
                                    otherBtn.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                                    otherBtn.setTextColor(Color.WHITE);
                                }
                                groupsWithLastUrl.clear();
                                groupsWithLastUrl.add(entry.getKey());
                            } else {
                                if (isDarkStyle) {
                                    otherBtn.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                                    otherBtn.setTextColor(DARK_TEXT_PRIMARY);
                                } else {
                                    otherBtn.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                                    otherBtn.setTextColor(TEXT_PRIMARY);
                                }
                            }
                        }

                        Object[] currentStateInfo = (Object[]) groupBtn.getTag();
                        boolean isExpanded = (Integer) currentStateInfo[0] == 1;
                        GridLayout gridContainer = (GridLayout) currentStateInfo[2];

                        if (isExpanded) {
                            // 收起内容
                            int groupBtnIndex = resultContainer.indexOfChild(groupBtn);
                            if (groupBtnIndex + 1 < resultContainer.getChildCount()) {
                                resultContainer.removeViewAt(groupBtnIndex + 1);
                            }

                            currentStateInfo[0] = 0; // 未展开
                            currentStateInfo[1] = 0; // 子项数量
                            currentStateInfo[2] = null; // 清空网格容器引用
                            groupBtn.setText(animeTitle + " (" + animeItems.size() + "集)");
                        } else {
                            // 展开内容 - 创建网格布局
                            int groupBtnIndex = resultContainer.indexOfChild(groupBtn);

                            sortResults(animeItems, isReversed);

                            // 创建网格布局容器
                            GridLayout gridLayout = new GridLayout(activity);
                            // 根据屏幕宽度动态计算列数
                            DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
                            int screenWidthPx = displayMetrics.widthPixels;
                            int screenWidthDp = (int) (screenWidthPx / displayMetrics.density);
                            int columns = Math.max(3, screenWidthDp / 120); // 每列约120dp
                            gridLayout.setColumnCount(columns);
                            gridLayout.setRowCount(GridLayout.UNDEFINED); // 行数自动计算
                            gridLayout.setUseDefaultMargins(false);
                            gridLayout.setPadding(dpToPx(activity, 20), dpToPx(activity, 12),
                                    dpToPx(activity, 20), dpToPx(activity, 12));
                            if (isDarkStyle) {
                                gridLayout.setBackgroundColor(Color.TRANSPARENT);
                            }

                            // 为每个剧集创建网格按钮
                            for (DanmakuItem item : animeItems) {
                                Button gridItem = isDarkStyle ? createDarkGridResultButton(activity, item, dialog) : createGridResultButton(activity, item, dialog);
                                gridLayout.addView(gridItem);
                            }

                            // 将网格布局添加到分组按钮后面
                            resultContainer.addView(gridLayout, groupBtnIndex + 1);

                            currentStateInfo[0] = 1; // 已展开
                            currentStateInfo[1] = animeItems.size(); // 子项数量
                            currentStateInfo[2] = gridLayout; // 保存网格容器引用
                            groupBtn.setText(animeTitle + " (" + animeItems.size() + "集) [-]");
                        }
                        groupBtn.setTag(currentStateInfo);

                        if (resultContainer.getParent() instanceof ScrollView) {
                            ScrollView scrollView = (ScrollView) resultContainer.getParent();
                            scrollView.post(new Runnable() {
                                @Override
                                public void run() {
                                    int scrollY = resultContainer.getTop() + groupBtn.getTop();
                                    scrollView.smoothScrollTo(0, scrollY);
                                }
                            });
                        }
                    }
                });

                resultContainer.addView(groupBtn);

                // 如果包含上次使用的URL，自动展开
                if (groupsWithLastUrl.contains(animeTitle)) {
                    groupBtn.post(new Runnable() {
                        @Override
                        public void run() {
                            groupBtn.performClick();
                            // 滚动到包含上次使用弹幕的项
                            resultContainer.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    Object[] stateInfo = (Object[]) groupBtn.getTag();
                                    GridLayout gridContainer = (GridLayout) stateInfo[2];
                                    if (gridContainer != null) {
                                        // 在网格中寻找包含lastDanmakuUrl的按钮
                                        for (int i = 0; i < gridContainer.getChildCount(); i++) {
                                            View child = gridContainer.getChildAt(i);
                                            if (child instanceof Button && child.getTag() instanceof DanmakuItem) {
                                                DanmakuItem item = (DanmakuItem) child.getTag();
                                                if (item.getDanmakuUrl() != null &&
                                                        item.getDanmakuUrl().equals(DanmakuManager.lastDanmakuUrl)) {
                                                    // 请求焦点
                                                    child.requestFocus();
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }, 100);
                        }
                    });
                }
            }
        } else {
            // 使用列表布局 (兼容旧版本或默认模板)
            for (int groupIndex = 0; groupIndex < animeTitles.size(); groupIndex++) {
                String animeTitle = animeTitles.get(groupIndex);
                List<DanmakuItem> animeItems = animeGroups.get(animeTitle);

                if (animeItems.size() == 1) {
                    DanmakuItem item = animeItems.get(0);
                    Button resultItem = createResultButton(activity, item, dialog, isDarkStyle);
                    resultContainer.addView(resultItem);
                } else {
                    Button groupBtn = new Button(activity);
                    groupBtn.setText(animeTitle + " (" + animeItems.size() + "集)");
                    groupBtn.setPadding(20, 10, 20, 10);
                    groupBtn.setTextSize(14);
                    groupBtn.setTypeface(null, android.graphics.Typeface.BOLD);

                    if (isDarkStyle) {
                        groupBtn.setTextColor(DARK_TEXT_PRIMARY);
                        if (groupsWithLastUrl.contains(animeTitle)) {
                            groupBtn.setBackground(createRoundedTransparentDrawable(grpSel));
                        } else {
                            groupBtn.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                        }
                     } else {
                        if (groupsWithLastUrl.contains(animeTitle)) {
                            groupBtn.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                            groupBtn.setTextColor(Color.WHITE);
                        } else {
                            groupBtn.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                            groupBtn.setTextColor(TEXT_PRIMARY);
                        }
                    }

                    groupBtn.setClickable(true);
                    groupBtn.setFocusable(true);

                    groupButtons.put(animeTitle, groupBtn);

                    groupBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View v, boolean hasFocus) {
                            Button button = (Button) v;
                            String title = null;

                            for (java.util.Map.Entry<String, Button> entry : groupButtons.entrySet()) {
                                if (entry.getValue() == v) {
                                    title = entry.getKey();
                                    break;
                                }
                            }

                            if (hasFocus) {
                                if (isDarkStyle) {
                                    v.setBackground(createRoundedTransparentDrawable(grpFoc));
                                    button.setTextColor(DARK_TEXT_PRIMARY);
                                } else {
                                    v.setBackground(createRoundedBackgroundDrawable(grpFocLight));
                                    button.setTextColor(Color.WHITE);
                                }
                                v.setScaleX(1.04f);
                                v.setScaleY(1.04f);
                            } else {
                                if (groupsWithLastUrl.contains(title)) {
                                    if (isDarkStyle) {
                                        v.setBackground(createRoundedTransparentDrawable(grpSel));
                                        button.setTextColor(DARK_TEXT_PRIMARY);
                                    } else {
                                        v.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                                        button.setTextColor(Color.WHITE);
                                    }
                                } else {
                                    if (isDarkStyle) {
                                        v.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                                        button.setTextColor(DARK_TEXT_PRIMARY);
                                    } else {
                                        v.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                                        button.setTextColor(TEXT_PRIMARY);
                                    }
                                }
                                v.setScaleX(1.0f);
                                v.setScaleY(1.0f);
                            }
                        }
                    });

                    int[] stateInfo = new int[]{0, 0};
                    groupBtn.setTag(stateInfo);

                    groupBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            for (java.util.Map.Entry<String, Button> entry : groupButtons.entrySet()) {
                                Button otherBtn = entry.getValue();
                                if (otherBtn == v) {
                                    if (isDarkStyle) {
                                        otherBtn.setBackground(createRoundedTransparentDrawable(grpSel));
                                        otherBtn.setTextColor(DARK_TEXT_PRIMARY);
                                    } else {
                                        otherBtn.setBackground(createRoundedBackgroundDrawable(grpSelLight));
                                        otherBtn.setTextColor(Color.WHITE);
                                    }
                                    groupsWithLastUrl.clear();
                                    groupsWithLastUrl.add(entry.getKey());
                                } else {
                                    if (isDarkStyle) {
                                        otherBtn.setBackground(createRoundedTransparentDrawable(DARK_BG_SECONDARY));
                                        otherBtn.setTextColor(DARK_TEXT_PRIMARY);
                                    } else {
                                        otherBtn.setBackground(createRoundedBackgroundDrawable(0xFFE8E8E8));
                                        otherBtn.setTextColor(TEXT_PRIMARY);
                                    }
                                }
                            }

                            int[] currentStateInfo = (int[]) groupBtn.getTag();
                            boolean isExpanded = currentStateInfo[0] == 1;

                            if (isExpanded) {
                                int buttonIndex = resultContainer.indexOfChild(groupBtn);
                                int childCount = currentStateInfo[1];
                                for (int i = 0; i < childCount; i++) {
                                    if (buttonIndex + 1 < resultContainer.getChildCount()) {
                                        resultContainer.removeViewAt(buttonIndex + 1);
                                    }
                                }
                                currentStateInfo[0] = 0;
                                currentStateInfo[1] = 0;
                                groupBtn.setText(animeTitle + " (" + animeItems.size() + "集)");
                            } else {
                                int buttonIndex = resultContainer.indexOfChild(groupBtn);
                                sortResults(animeItems, isReversed);
                                int containerWidth = resultContainer.getMeasuredWidth();
                                if (containerWidth == 0) {
                                    containerWidth = activity.getResources().getDisplayMetrics().widthPixels
                                            - dpToPx(activity, 48);
                                }
                                int availWidth = containerWidth - dpToPx(activity, 40);
                                Button[] subButtons = new Button[animeItems.size()];
                                String[] fullTitles = new String[animeItems.size()];
                                for (int i = 0; i < animeItems.size(); i++) {
                                    DanmakuItem item = animeItems.get(i);
                                    subButtons[i] = createResultButton(activity, item, dialog, isDarkStyle);
                                    subButtons[i].setPadding(dpToPx(activity, 6), dpToPx(activity, 6), dpToPx(activity, 6), dpToPx(activity, 6));
                                    fullTitles[i] = item.getTitleWithEp();
                                }
                                String[] stripped = stripCommonParts(fullTitles);
                                for (int i = 0; i < subButtons.length; i++) {
                                    subButtons[i].setText("  " + stripped[i] + "  ");
                                }
                                int maxWidth = 0;
                                for (Button btn : subButtons) {
                                    btn.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                                    int w = btn.getMeasuredWidth();
                                    if (w > maxWidth) maxWidth = w;
                                }
                                int marginTotal = dpToPx(activity, 16);
                                int columns = Math.max(1, availWidth / dpToPx(activity, 90));
                                columns = Math.min(animeItems.size(), Math.min(columns, 4));
                                int colWidth = (availWidth - marginTotal * columns) / columns;
                                int maxHeight = 0;
                                for (Button btn : subButtons) {
                                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
                                    lp.width = colWidth;
                                    btn.setLayoutParams(lp);
                                    btn.measure(View.MeasureSpec.makeMeasureSpec(colWidth, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.UNSPECIFIED);
                                    int h = btn.getMeasuredHeight();
                                    if (h > maxHeight) maxHeight = h;
                                }
                                for (Button btn : subButtons) {
                                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
                                    lp.width = colWidth;
                                    lp.height = maxHeight;
                                    btn.setLayoutParams(lp);
                                }
                                LinearLayout outerWrap = new LinearLayout(activity);
                                outerWrap.setOrientation(LinearLayout.VERTICAL);
                                outerWrap.setGravity(Gravity.CENTER_HORIZONTAL);
                                outerWrap.setLayoutParams(new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                                outerWrap.setPadding(dpToPx(activity, 20), dpToPx(activity, 12),
                                        dpToPx(activity, 20), dpToPx(activity, 12));
                                LinearLayout currentRow = null;
                                for (int i = 0; i < subButtons.length; i++) {
                                    if (i % columns == 0) {
                                        currentRow = new LinearLayout(activity);
                                        currentRow.setOrientation(LinearLayout.HORIZONTAL);
                                        currentRow.setGravity(Gravity.CENTER);
                                        outerWrap.addView(currentRow);
                                    }
                                    currentRow.addView(subButtons[i]);
                                }
                                resultContainer.addView(outerWrap, buttonIndex + 1);
                                currentStateInfo[0] = 1;
                                currentStateInfo[1] = 1;
                                groupBtn.setText(animeTitle + " (" + animeItems.size() + "集) [-]");
                            }
                            groupBtn.setTag(currentStateInfo);

                            if (resultContainer.getParent() instanceof ScrollView) {
                                ScrollView scrollView = (ScrollView) resultContainer.getParent();
                                scrollView.post(() -> {
                                    int scrollY = resultContainer.getTop() + groupBtn.getTop();
                                    scrollView.smoothScrollTo(0, scrollY);
                                });
                            }
                        }
                    });

                    resultContainer.addView(groupBtn);

                    if (groupsWithLastUrl.contains(animeTitle)) {
                        groupBtn.post(() -> {
                            groupBtn.performClick();
                            resultContainer.post(() -> {
                                View targetView = findDanmakuButton(resultContainer);

                                if (resultContainer.getParent() instanceof ScrollView) {
                                    ScrollView scrollView = (ScrollView) resultContainer.getParent();
                                    View finalTargetView = targetView;
                                    scrollView.post(() -> {
                                        if (finalTargetView != null) {
                                            int scrollY = resultContainer.getTop() + finalTargetView.getTop();
                                            scrollView.smoothScrollTo(0, scrollY);
                                        } else {
                                            int scrollY = resultContainer.getTop() + groupBtn.getTop();
                                            scrollView.smoothScrollTo(0, scrollY);
                                        }
                                    });
                                }
                            });
                        });
                    }
                }
            }
        }

        resultContainer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private static void sortResults(List<DanmakuItem> results, boolean reversed) {
        java.util.Collections.sort(results, new java.util.Comparator<DanmakuItem>() {
            @Override
            public int compare(DanmakuItem item1, DanmakuItem item2) {
                // 基于 epId 进行排序，假设 epId 是 String 或可比较类型 [1]
                if (item1.epId == null || item2.epId == null) return 0;
                int cmp = item1.epId.compareTo(item2.epId);
                return reversed ? -cmp : cmp; // 根据状态决定正序或倒序
            }
        });
    }



    // 在 resultContainer 中递归查找按钮（支持 GridLayout 嵌套）
    private static View findDanmakuButton(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof Button && child.getTag() instanceof DanmakuItem) {
                DanmakuItem item = (DanmakuItem) child.getTag();
                if (item.getDanmakuUrl() != null && item.getDanmakuUrl().equals(DanmakuManager.lastDanmakuUrl)) {
                    return child;
                }
            } else if (child instanceof ViewGroup) {
                View found = findDanmakuButton((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // 去除所有标题中的公共前缀和后缀，只保留差异部分
    private static String[] stripCommonParts(String[] titles) {
        if (titles.length <= 1) return titles;
        String prefix = titles[0];
        for (int i = 1; i < titles.length; i++) {
            while (!titles[i].startsWith(prefix)) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) break;
            }
            if (prefix.isEmpty()) break;
        }
        String suffix = titles[0].substring(prefix.length());
        for (int i = 1; i < titles.length; i++) {
            String rest = titles[i].substring(prefix.length());
            while (!rest.endsWith(suffix)) {
                suffix = suffix.substring(1);
                if (suffix.isEmpty()) break;
            }
            if (suffix.isEmpty()) break;
        }
        String[] result = new String[titles.length];
        for (int i = 0; i < titles.length; i++) {
            String s = titles[i].substring(prefix.length());
            if (!suffix.isEmpty() && s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
            }
            s = s.trim();
            result[i] = s.isEmpty() ? titles[i] : s;
        }
        return result;
    }

    // 创建结果按钮的辅助方法 - 改进版本
    private static Button createResultButton(Activity activity, DanmakuItem item, AlertDialog dialog, boolean isDarkStyle) {
        Button resultItem = new Button(activity);
        resultItem.setFocusable(true);
        resultItem.setFocusableInTouchMode(true);
        resultItem.setClickable(true);
        resultItem.setMinWidth(0);
        resultItem.setMinHeight(0);
        resultItem.setText("  " + item.getTitleWithEp() + "  ");
        resultItem.setTextSize(13);
        resultItem.setEllipsize(null);
        resultItem.setHorizontallyScrolling(false);
        resultItem.setSingleLine(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            resultItem.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        }
        resultItem.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(activity, 8), dpToPx(activity, 6), dpToPx(activity, 8), dpToPx(activity, 6));
        resultItem.setLayoutParams(params);

        String currentDanmakuUrl = item.getDanmakuUrl();
        boolean isSelected = currentDanmakuUrl != null && currentDanmakuUrl.equals(DanmakuManager.lastDanmakuUrl);

        int accentColor, accentDark, normalBg, selectedBg;
        if (isDarkStyle) {
            accentColor = ACCENT_T4;
            accentDark = 0xCC0A84FF;
            normalBg = DARK_TAB_BG;
            selectedBg = 0x800A84FF;
        } else {
            accentColor = PRIMARY_COLOR;
            accentDark = 0xFF0056B3;
            normalBg = ITEM_BG_T1;
            selectedBg = PRIMARY_COLOR;
        }

        if (isDarkStyle) {
            resultItem.setBackground(createRoundedTransparentDrawable(isSelected ? selectedBg : normalBg));
            resultItem.setTextColor(DARK_TEXT_PRIMARY);
        } else {
            resultItem.setBackground(createRoundedBackgroundDrawable(isSelected ? selectedBg : normalBg));
            resultItem.setTextColor(isSelected ? Color.WHITE : TEXT_PRIMARY);
        }

        resultItem.setTag(item);

        final int finalAccentDark = accentDark;
        final int finalAccentColor = accentColor;
        resultItem.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                DanmakuItem item_tag = (DanmakuItem) v.getTag();
                String danmakuUrl = item_tag.getDanmakuUrl();
                boolean isCurrentlySelected = danmakuUrl != null && danmakuUrl.equals(DanmakuManager.lastDanmakuUrl);

                if (hasFocus) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor((finalAccentColor & 0x00FFFFFF) | 0x20000000);
                    gd.setStroke(4, finalAccentColor);
                    gd.setCornerRadius(18);
                    v.setBackground(gd);
                    ((Button) v).setTextColor(isDarkStyle ? DARK_TEXT_PRIMARY : finalAccentColor);
                    v.setScaleX(1.04f);
                    v.setScaleY(1.04f);
                } else {
                    if (isDarkStyle) {
                        v.setBackground(createRoundedTransparentDrawable(isCurrentlySelected ? selectedBg : normalBg));
                        ((Button) v).setTextColor(DARK_TEXT_PRIMARY);
                    } else {
                        v.setBackground(createRoundedBackgroundDrawable(isCurrentlySelected ? selectedBg : normalBg));
                        ((Button) v).setTextColor(isCurrentlySelected ? Color.WHITE : TEXT_PRIMARY);
                    }
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            }
        });

        resultItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                DanmakuItem selected = (DanmakuItem) v1.getTag();
                // 记录弹幕URL
                DanmakuSpider.recordDanmakuUrl(selected, false);
                LeoDanmakuService.pushDanmakuDirect(selected, activity, false);
                dialog.dismiss();
            }
        });

        return resultItem;
    }

    // 创建网格布局结果按钮的辅助方法
    private static Button createGridResultButton(Activity activity, DanmakuItem item, AlertDialog dialog) {
        Button resultItem = new Button(activity);
        resultItem.setFocusable(true);
        resultItem.setFocusableInTouchMode(true);
        resultItem.setClickable(true);

        // 缩短文本显示，适合网格布局
        String displayText = DanmakuScanner.extractEpisodeNum(item.epTitle);
        if (TextUtils.isEmpty(displayText)) {
            // 安全地获取分割后的第二部分，避免数组越界
            String[] parts = item.epTitle != null ? item.epTitle.split(" ") : new String[0];
            if (parts.length > 1) {
                displayText = parts[1];
            } else if (parts.length > 0) {
                displayText = parts[0]; // 如果只有一个部分，使用第一部分
            } else {
                displayText = item.epTitle != null ? item.epTitle : "未知"; // 如果没有分割部分，使用原字符串或默认值
            }
        }

        resultItem.setText(displayText);
        resultItem.setTextSize(13); // 增大字号

        // 设置内边距
        int padding = dpToPx(activity, 10);
        resultItem.setPadding(padding, padding, padding, padding);

        // 设置单行显示，超出部分...省略
        resultItem.setSingleLine(true);
        resultItem.setEllipsize(TextUtils.TruncateAt.END);

        // 设置文本居中
        resultItem.setGravity(Gravity.CENTER);

        // 安全设置工具提示（完整标题）- 仅在 API 26+ 可用
        // 当按钮获得焦点或长按时，会显示完整的标题
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            resultItem.setTooltipText(item.getTitleWithEp());
        }

        // 设置圆角背景 - 使用模板二暖橙色
        final int accentGrid = ACCENT_T2;
        final int accentGridDark = 0xFFC97D0A;
        String currentDanmakuUrl = item.getDanmakuUrl();
        if (currentDanmakuUrl != null && currentDanmakuUrl.equals(DanmakuManager.lastDanmakuUrl)) {
            // 高亮显示 - 使用暖橙色背景带边框
            resultItem.setBackground(createBorderDrawable(accentGrid, 0xFFFFF3E0, 12));
            resultItem.setTextColor(Color.WHITE);
        } else {
            // 普通显示 - 白色底带浅灰边框
            resultItem.setBackground(createBorderDrawable(0xFFFFFFFF, LIGHT_GRID_BORDER, 12));
            resultItem.setTextColor(TEXT_PRIMARY);
        }

        // 设置网格布局参数
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        } else {
            params.width = dpToPx(activity, 80);
        }

        // 设置外边距
        int margin = dpToPx(activity, 6);
        params.setMargins(margin, margin, margin, margin);

        resultItem.setLayoutParams(params);
        resultItem.setTag(item);

        resultItem.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(Color.TRANSPARENT);
                    gd.setStroke(4, accentGrid);
                    gd.setCornerRadius(12);
                    v.setBackground(gd);
                    ((Button) v).setTextColor(accentGrid);
                    v.setScaleX(1.03f);
                    v.setScaleY(1.03f);
                } else {
                    DanmakuItem item_tag = (DanmakuItem) v.getTag();
                    String danmakuUrl = item_tag.getDanmakuUrl();
                    if (danmakuUrl != null && danmakuUrl.equals(DanmakuManager.lastDanmakuUrl)) {
                        v.setBackground(createBorderDrawable(accentGrid, 0xFFFFF3E0, 12));
                        ((Button) v).setTextColor(Color.WHITE);
                    } else {
                        v.setBackground(createBorderDrawable(0xFFFFFFFF, LIGHT_GRID_BORDER, 12));
                        ((Button) v).setTextColor(TEXT_PRIMARY);
                    }
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            }
        });

        resultItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                DanmakuItem selected = (DanmakuItem) v1.getTag();
                // 记录弹幕URL
                DanmakuSpider.recordDanmakuUrl(selected, false);
                LeoDanmakuService.pushDanmakuDirect(selected, activity, false);
                dialog.dismiss();
            }
        });

        // 长按显示完整标题 - 这个功能在所有版本上都可用，作为低版本API的兼容
        resultItem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                DanmakuItem item = (DanmakuItem) v.getTag();
                Utils.safeShowToast(activity, item.getTitleWithEp(),  true);
                return true;
            }
        });

        return resultItem;
    }

    public static void showQRCodeDialog(Activity activity, String url) {
        activity.runOnUiThread(() -> {
            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setGravity(Gravity.CENTER);

                ImageView qrCodeView = new ImageView(activity);
                mainLayout.addView(qrCodeView);

                TextView urlView = new TextView(activity);
                urlView.setText(url);
                urlView.setTextColor(android.graphics.Color.WHITE);
                urlView.setGravity(Gravity.CENTER);
                urlView.setPadding(0, dpToPx(activity, 10), 0, 0);
                mainLayout.addView(urlView);

                TextView hintView = new TextView(activity);
                hintView.setText("扫码搜索弹幕");
                hintView.setTextColor(android.graphics.Color.WHITE);
                hintView.setGravity(Gravity.CENTER);
                hintView.setTextSize(14);
                hintView.setPadding(0, dpToPx(activity, 6), 0, 0);
                mainLayout.addView(hintView);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

                new Thread(() -> {
                    try {
                        String qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=533x533&data=" + URLEncoder.encode(url, "UTF-8");
                        try (Response response = OkHttp.newCall(qrCodeUrl, "qrcode")) {
                            if (response.body() != null) {
                                InputStream in = response.body().byteStream();
                                Bitmap bitmap = BitmapFactory.decodeStream(in);
                                activity.runOnUiThread(() -> qrCodeView.setImageBitmap(bitmap));
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                safeShowDialog(activity, dialog);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // 创建透明圆角背景
    private static android.graphics.drawable.Drawable createRoundedTransparentDrawable(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(18);
        return drawable;
    }

    // 创建深色主题网格布局结果按钮
    private static Button createDarkGridResultButton(Activity activity, DanmakuItem item, AlertDialog dialog) {
        Button resultItem = new Button(activity);
        resultItem.setFocusable(true);
        resultItem.setFocusableInTouchMode(true);
        resultItem.setClickable(true);

        // 缩短文本显示，适合网格布局 (统一为模板二的逻辑)
        String displayText = DanmakuScanner.extractEpisodeNum(item.epTitle);
        if (TextUtils.isEmpty(displayText)) {
            // 安全地获取分割后的第二部分，避免数组越界
            String[] parts = item.epTitle != null ? item.epTitle.split(" ") : new String[0];
            if (parts.length > 1) {
                displayText = parts[1];
            } else if (parts.length > 0) {
                displayText = parts[0]; // 如果只有一个部分，使用第一部分
            } else {
                displayText = item.epTitle != null ? item.epTitle : "未知"; // 如果没有分割部分，使用原字符串或默认值
            }
        }

        resultItem.setText(displayText);
        resultItem.setTextSize(13); // 增大字号
        resultItem.setTextColor(DARK_TEXT_PRIMARY); // 白色文字

        // 设置内边距
        int padding = dpToPx(activity, 10);
        resultItem.setPadding(padding, padding, padding, padding);

        // 设置单行显示，超出部分...省略 (统一为模板二的逻辑)
        resultItem.setSingleLine(true);
        resultItem.setEllipsize(TextUtils.TruncateAt.END);

        // 设置文本居中
        resultItem.setGravity(Gravity.CENTER);

        // 安全设置工具提示（完整标题）- 仅在 API 26+ 可用
        // 添加版本检查避免在低版本上崩溃
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            resultItem.setTooltipText(item.getTitleWithEp());
        }

        // 设置圆角背景 - 使用模板三翠绿色
        final int accentDarkGrid = ACCENT_T3;
        String currentDanmakuUrl = item.getDanmakuUrl();
        if (currentDanmakuUrl != null && currentDanmakuUrl.equals(DanmakuManager.lastDanmakuUrl)) {
            // 高亮显示 - 翠绿半透明背景带边框
            resultItem.setBackground(createDarkBorderDrawable(0x6035C958, DARK_GRID_BORDER, 12));
            resultItem.setTextColor(DARK_TEXT_PRIMARY);
        } else {
            // 普通显示 - 深灰底带深灰边框
            resultItem.setBackground(createDarkBorderDrawable(DARK_GRID_ITEM, DARK_GRID_BORDER, 12));
            resultItem.setTextColor(DARK_TEXT_PRIMARY);
        }

        // 设置网格布局参数
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.height = GridLayout.LayoutParams.WRAP_CONTENT; // 高度自适应

        // 兼容安卓7.0以下版本
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 在Android 7.0 (API 24) 及以上版本使用权重实现等宽
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        } else {
            // 在旧版本上使用固定宽度，以兼容旧版GridLayout
            params.width = dpToPx(activity, 80); // 约4个字符宽度
        }

        // 设置外边距
        int margin = dpToPx(activity, 6);
        params.setMargins(margin, margin, margin, margin);

        resultItem.setLayoutParams(params);
        resultItem.setTag(item);

        resultItem.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(Color.TRANSPARENT);
                    gd.setStroke(4, accentDarkGrid);
                    gd.setCornerRadius(12);
                    v.setBackground(gd);
                    ((Button) v).setTextColor(DARK_TEXT_PRIMARY);
                    v.setScaleX(1.03f);
                    v.setScaleY(1.03f);
                } else {
                    DanmakuItem item_tag = (DanmakuItem) v.getTag();
                    String danmakuUrl = item_tag.getDanmakuUrl();
                    if (danmakuUrl != null && danmakuUrl.equals(DanmakuManager.lastDanmakuUrl)) {
                        v.setBackground(createDarkBorderDrawable(0x6035C958, DARK_GRID_BORDER, 12));
                        ((Button) v).setTextColor(DARK_TEXT_PRIMARY);
                    } else {
                        v.setBackground(createDarkBorderDrawable(DARK_GRID_ITEM, DARK_GRID_BORDER, 12));
                        ((Button) v).setTextColor(DARK_TEXT_PRIMARY);
                    }
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            }
        });

        resultItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                DanmakuItem selected = (DanmakuItem) v1.getTag();
                // 记录弹幕URL
                DanmakuSpider.recordDanmakuUrl(selected, false);
                LeoDanmakuService.pushDanmakuDirect(selected, activity, false);
                dialog.dismiss();
            }
        });

        // 长按显示完整标题 - 这个功能在所有版本上都可用
        resultItem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                DanmakuItem item = (DanmakuItem) v.getTag();
                Utils.safeShowToast(activity, item.getTitleWithEp(),  true);
                return true;
            }
        });

        return resultItem;
    }

    // 根据模板样式返回对应的强调色
    private static int getTemplateAccent(String style) {
        if (style.equals("模板二")) return ACCENT_T2;
        if (style.equals("模板三")) return ACCENT_T3;
        if (style.equals("模板四")) return ACCENT_T4;
        return PRIMARY_COLOR; // 模板一
    }

    // 创建深色主题实心按钮
    private static Button createDarkSolidButton(Activity activity, String text, int backgroundColor) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(DARK_TEXT_PRIMARY);
        button.setBackground(createRoundedTransparentDrawable(backgroundColor));
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);

        // 添加焦点效果 - 高亮边框
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    android.graphics.drawable.GradientDrawable focusDrawable = new android.graphics.drawable.GradientDrawable();
                    focusDrawable.setColor(lightenColor(backgroundColor, 0.4f));
                    focusDrawable.setStroke(4, 0xFF00D4FF);
                    focusDrawable.setCornerRadius(18);
                    v.setBackground(focusDrawable);
                    ((Button) v).setTextColor(DARK_TEXT_PRIMARY);
                    v.setScaleX(1.04f);
                    v.setScaleY(1.04f);
                } else {
                    ((Button) v).setBackground(createRoundedTransparentDrawable(backgroundColor));
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
            }
        });

        return button;
    }

    // 颜色加深辅助方法
    private static int darkenColor(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * (1 - factor));
        int g = (int) (((color >> 8) & 0xFF) * (1 - factor));
        int b = (int) ((color & 0xFF) * (1 - factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
