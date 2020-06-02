/*
 * Copyright 2019 Pnoker. All Rights Reserved.
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

package com.dc3.driver.service.impl;

import com.alibaba.fastjson.JSON;
import com.dc3.common.constant.Common;
import com.dc3.common.model.Device;
import com.dc3.common.model.Point;
import com.dc3.common.sdk.bean.AttributeInfo;
import com.dc3.common.sdk.service.DriverService;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.locator.BaseLocator;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static com.dc3.common.sdk.util.DriverUtils.attribute;

/**
 * @author pnoker
 */
@Slf4j
@Service
public class DriverServiceImpl implements DriverService {
    static ModbusFactory modbusFactory;

    static {
        modbusFactory = new ModbusFactory();
    }

    private volatile Map<Long, ModbusMaster> masterMap = new HashMap<>(64);

    @Override
    public void initial() {
    }

    @Override
    @SneakyThrows
    public String read(Map<String, AttributeInfo> driverInfo, Map<String, AttributeInfo> pointInfo, Device device, Point point) {
        ModbusMaster modbusMaster = getMaster(device.getId(), driverInfo);
        String value = readValue(modbusMaster, pointInfo, point.getType());
        return value;
    }

    @Override
    @SneakyThrows
    public Boolean write(Map<String, AttributeInfo> driverInfo, Map<String, AttributeInfo> pointInfo, Device device, AttributeInfo value) {
        return false;
    }

    @Override
    public void schedule() {
    }

    /**
     * 获取 Modbus Master
     *
     * @param deviceId
     * @param driverInfo
     * @return
     * @throws ModbusInitException
     */
    public ModbusMaster getMaster(Long deviceId, Map<String, AttributeInfo> driverInfo) throws ModbusInitException {
        log.debug("Modbus Tcp Connection Info {}", JSON.toJSONString(driverInfo));
        ModbusMaster modbusMaster = masterMap.get(deviceId);
        if (null == modbusMaster || !modbusMaster.isConnected()) {
            IpParameters params = new IpParameters();
            params.setHost(attribute(driverInfo, "host"));
            params.setPort(attribute(driverInfo, "port"));
            modbusMaster = modbusFactory.createTcpMaster(params, true);
            modbusMaster.init();
            masterMap.put(deviceId, modbusMaster);
        }
        return modbusMaster;
    }

    /**
     * 获取 Value
     *
     * @param modbusMaster
     * @param pointInfo
     * @return
     * @throws ModbusTransportException
     * @throws ErrorResponseException
     * @throws ModbusInitException
     */
    public String readValue(ModbusMaster modbusMaster, Map<String, AttributeInfo> pointInfo, String type) throws ModbusTransportException, ErrorResponseException, ModbusInitException {
        int slaveId = attribute(pointInfo, "slaveId");
        int functionCode = attribute(pointInfo, "functionCode");
        int offset = attribute(pointInfo, "offset");
        switch (functionCode) {
            case 1:
                BaseLocator<Boolean> coilLocator = BaseLocator.coilStatus(slaveId, offset);
                Boolean coilValue = modbusMaster.getValue(coilLocator);
                return String.valueOf(coilValue);
            case 2:
                BaseLocator<Boolean> inputLocator = BaseLocator.inputStatus(slaveId, offset);
                Boolean inputStatusValue = modbusMaster.getValue(inputLocator);
                return String.valueOf(inputStatusValue);
            case 3:
                BaseLocator<Number> holdingLocator = BaseLocator.holdingRegister(slaveId, offset, getValueType(type));
                Number holdingValue = modbusMaster.getValue(holdingLocator);
                return String.valueOf(holdingValue);
            case 4:
                BaseLocator<Number> inputRegister = BaseLocator.inputRegister(slaveId, offset, getValueType(type));
                Number inputRegisterValue = modbusMaster.getValue(inputRegister);
                return String.valueOf(inputRegisterValue);
            default:
                return "0";
        }
    }

    public void writeValue(String type) {
        switch (type.toLowerCase()) {
            case Common.ValueType.INT:
                break;
            case Common.ValueType.LONG:
                break;
            case Common.ValueType.FLOAT:
                break;
            case Common.ValueType.DOUBLE:
                break;
            case Common.ValueType.BOOLEAN:
                break;
            case Common.ValueType.STRING:
                break;
            default:
                break;
        }
    }

    /**
     * 获取数据类型
     *
     * @param type
     * @return
     */
    public int getValueType(String type) {
        switch (type.toLowerCase()) {
            case Common.ValueType.INT:
                return DataType.FOUR_BYTE_INT_SIGNED;
            case Common.ValueType.LONG:
                return DataType.EIGHT_BYTE_INT_SIGNED;
            case Common.ValueType.FLOAT:
                return DataType.FOUR_BYTE_FLOAT;
            case Common.ValueType.DOUBLE:
                return DataType.EIGHT_BYTE_FLOAT;
            case Common.ValueType.BOOLEAN:
                return DataType.TWO_BYTE_INT_SIGNED;
            default:
                return DataType.VARCHAR;
        }
    }

}
