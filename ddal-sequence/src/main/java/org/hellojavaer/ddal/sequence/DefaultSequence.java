/*
 * Copyright 2017-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hellojavaer.ddal.sequence;

import org.hellojavaer.ddal.sequence.exception.NoAvailableIdRangeFoundException;
import org.hellojavaer.ddal.sequence.utils.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 步长
 * 阈值
 * 数据安全
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 03/01/2017.
 */
public class DefaultSequence implements Sequence {

    private Logger           logger      = LoggerFactory.getLogger(this.getClass());
    private String           schemaName;
    private String           tableName;
    private Integer          step;                                                  // 单节点步长
    private Integer          cacheNSteps;                                           // 缓存队列大小
    private Integer          timeout;
    private IdGetter         idGetter;

    private volatile IdCache idCache;
    private boolean          initialized = false;

    private ExceptionHandler exceptionHandler;

    public DefaultSequence() {
    }

    public DefaultSequence(String schemaName, String tableName, Integer step, Integer cacheNSteps, Integer timeout,
                           IdGetter idGetter, ExceptionHandler exceptionHandler) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.step = step;
        this.cacheNSteps = cacheNSteps;
        this.timeout = timeout;
        this.idGetter = idGetter;
        this.exceptionHandler = exceptionHandler;
        init();
    }

    public void init() {
        if (initialized == false) {
            synchronized (this) {
                if (initialized == false) {
                    // init
                    Assert.notNull(schemaName, "'schemaName' can't be null'");
                    Assert.notNull(tableName, "'tableName' can't be null'");
                    Assert.notNull(step, "'step' must be greater than 0");
                    Assert.notNull(cacheNSteps, "'cacheNSteps' must be greater than or equal to 0");
                    Assert.notNull(timeout, "'timeout' must be greater than 0");
                    Assert.notNull(idGetter, "'idGetter' can't be null'");
                    idCache = getIdCache();
                    initialized = true;
                }
            }
        }
    }

    protected IdCache getIdCache() {
        if (cacheNSteps <= 0) {
            throw new IllegalArgumentException("cacheNSteps[" + cacheNSteps + "] must greater then 0");
        }
        return new IdCache(step, cacheNSteps, exceptionHandler) {

            @Override
            public IdRange getIdRange() throws Exception {
                IdRange idRange = getIdGetter().get(getSchemaName(), getTableName(), getStep());
                if (idRange == null) {
                    throw new NoAvailableIdRangeFoundException("No available id rang was found for schemaName:'"
                                                               + getSchemaName() + "', tableName:'" + getTableName()
                                                               + "'");
                } else {
                    return idRange;
                }
            }
        };
    }

    @Override
    public long nextValue() {
        init();
        try {
            return idCache.get(timeout);
        } catch (RuntimeException e0) {
            throw e0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public Integer getCacheNSteps() {
        return cacheNSteps;
    }

    public void setCacheNSteps(Integer cacheNSteps) {
        this.cacheNSteps = cacheNSteps;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public IdGetter getIdGetter() {
        return idGetter;
    }

    public void setIdGetter(IdGetter idGetter) {
        this.idGetter = idGetter;
    }

    public ExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }
}
