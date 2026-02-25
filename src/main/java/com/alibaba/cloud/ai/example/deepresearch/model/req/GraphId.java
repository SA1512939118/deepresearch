/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.deepresearch.model.req;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * @author vlsmb
 * @since 2025/8/6
 *
 * record：
 * 这是 Java 16 引入的记录类型，用于定义不可变的数据载体（类似简化的不可变类）。
 * 编译器会自动生成：
 * 私有最终字段：sessionId、threadId
 * 构造器：GraphId(String sessionId, String threadId)
 * 访问器方法：sessionId()、threadId()
 * equals()、hashCode()、toString()
 * 对象是不可变的，字段设值后不能再修改。
 */
public record GraphId(@JsonProperty("session_id") String sessionId,
		@JsonProperty("thread_id") String threadId) implements Serializable {
}
