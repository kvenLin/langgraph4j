package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 视觉标记工具，用于在页面上添加元素标记并记录坐标信息
 */
@Slf4j
public class VisualMarker {
    private final Page page;
    private final String markerScript;
    private final ObjectMapper objectMapper;
    private final File coordsFile;

    @Data
    public static class ElementCoords {
        private int x;
        private int y;
        private int width;
        private int height;
        private int centerX;
        private int centerY;
    }

    public VisualMarker(Page page, String coordsFilePath) {
        this.page = page;
        this.objectMapper = new ObjectMapper();
        this.coordsFile = new File(coordsFilePath);
        
        // 加载标记脚本
        try {
            this.markerScript = """
                function markElements() {
                    const labels = [];
                    
                    // 清除已有标记
                    function unmarkPage() {
                        for (const label of labels) {
                            document.body.removeChild(label);
                        }
                        labels.length = 0;
                    }
                    
                    unmarkPage();
                    
                    // 获取可视区域大小
                    const vw = Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0);
                    const vh = Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0);
                    
                    // 查找可交互元素
                    const items = Array.from(document.querySelectorAll('*'))
                        .map(element => {
                            const rects = Array.from(element.getClientRects())
                                .filter(bb => {
                                    const centerX = bb.left + bb.width / 2;
                                    const centerY = bb.top + bb.height / 2;
                                    const elAtCenter = document.elementFromPoint(centerX, centerY);
                                    return elAtCenter === element || element.contains(elAtCenter);
                                })
                                .map(bb => ({
                                    left: Math.max(0, bb.left),
                                    top: Math.max(0, bb.top),
                                    right: Math.min(vw, bb.right),
                                    bottom: Math.min(vh, bb.bottom),
                                    width: Math.min(vw, bb.right) - Math.max(0, bb.left),
                                    height: Math.min(vh, bb.bottom) - Math.max(0, bb.top)
                                }));
                                
                            return {
                                element,
                                include: (
                                    element.tagName === "INPUT" ||
                                    element.tagName === "TEXTAREA" ||
                                    element.tagName === "SELECT" ||
                                    element.tagName === "BUTTON" ||
                                    element.tagName === "A" ||
                                    element.onclick != null ||
                                    window.getComputedStyle(element).cursor === "pointer"
                                ),
                                rects,
                                text: element.textContent.trim().replace(/\\s{2,}/g, ' ')
                            };
                        })
                        .filter(item => item.include && item.rects.length > 0);
                    
                    // 记录元素坐标
                    const coords = {};
                    
                    items.forEach((item, index) => {
                        const rect = item.rects[0];
                        coords[index] = {
                            x: Math.round(rect.left),
                            y: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height),
                            centerX: Math.round(rect.left + rect.width / 2),
                            centerY: Math.round(rect.top + rect.height / 2)
                        };
                        
                        // 创建标记
                        const marker = document.createElement('div');
                        marker.style.cssText = `
                            position: fixed;
                            left: ${rect.left}px;
                            top: ${rect.top}px;
                            width: ${rect.width}px;
                            height: ${rect.height}px;
                            outline: 2px dashed #${Math.floor(Math.random()*16777215).toString(16)};
                            pointer-events: none;
                            z-index: 2147483647;
                        `;
                        
                        // 添加编号标签
                        const label = document.createElement('span');
                        label.textContent = index;
                        label.style.cssText = `
                            position: absolute;
                            top: -19px;
                            left: 0;
                            background: #${Math.floor(Math.random()*16777215).toString(16)};
                            color: white;
                            padding: 2px 4px;
                            font-size: 12px;
                            border-radius: 2px;
                        `;
                        
                        marker.appendChild(label);
                        document.body.appendChild(marker);
                        labels.push(marker);
                    });
                    
                    return coords;
                }
                """;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load marker script", e);
        }
    }

    /**
     * 标记页面元素并保存坐标信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, ElementCoords> markElements() {
        try {
            // 执行标记脚本
            String script = """
                (() => {
                    %s
                    return markElements();
                })();
                """.formatted(markerScript);
                
            Object result = page.evaluate(script);
            
            // 将结果转换为坐标映射
            Map<String, Map<String, Object>> rawCoords = (Map<String, Map<String, Object>>) result;
            Map<String, ElementCoords> coords = new HashMap<>();
            
            rawCoords.forEach((key, value) -> {
                ElementCoords coord = new ElementCoords();
                coord.setX(((Number) value.get("x")).intValue());
                coord.setY(((Number) value.get("y")).intValue());
                coord.setWidth(((Number) value.get("width")).intValue());
                coord.setHeight(((Number) value.get("height")).intValue());
                coord.setCenterX(((Number) value.get("centerX")).intValue());
                coord.setCenterY(((Number) value.get("centerY")).intValue());
                coords.put(key, coord);
            });
            
            // 保存坐标信息到文件
            objectMapper.writeValue(coordsFile, coords);
            log.info("已保存元素坐标信息到文件: {}", coordsFile.getAbsolutePath());
            
            return coords;
        } catch (IOException e) {
            throw new RuntimeException("标记元素或保存坐标失败", e);
        }
    }

    /**
     * 从文件加载坐标信息
     */
    public Map<String, ElementCoords> loadCoords() {
        try {
            if (!coordsFile.exists()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(coordsFile, 
                objectMapper.getTypeFactory().constructMapType(
                    Map.class, String.class, ElementCoords.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load coordinates from file", e);
        }
    }

    /**
     * 清除页面上的标记
     */
    public void clearMarkers() {
        page.evaluate("function() { const labels = document.querySelectorAll('div[style*=\"outline: 2px dashed\"]'); labels.forEach(label => label.remove()); }");
    }
}
