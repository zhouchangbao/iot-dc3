/*
 * Copyright 2018-2020 Pnoker. All Rights Reserved.
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

package com.dc3.common.sdk.service.impl;

import cn.hutool.core.thread.ThreadUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dc3.api.center.manager.feign.*;
import com.dc3.common.bean.Pages;
import com.dc3.common.bean.R;
import com.dc3.common.dto.*;
import com.dc3.common.exception.ServiceException;
import com.dc3.common.model.*;
import com.dc3.common.sdk.bean.AttributeInfo;
import com.dc3.common.sdk.bean.DriverContext;
import com.dc3.common.sdk.bean.DriverProperty;
import com.dc3.common.sdk.service.DriverConfigurationService;
import com.dc3.common.utils.Dc3Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author pnoker
 */
@Slf4j
@Service
@EnableConfigurationProperties({DriverProperty.class})
public class DriverConfigurationServiceImpl implements DriverConfigurationService {

    @Value("${server.port}")
    private int port;
    @Value("${spring.application.name}")
    private String serviceName;

    private String localHost;

    private Map<Long, PointAttribute> pointAttributeMap;
    private Map<Long, DriverAttribute> driverAttributeMap;

    @Resource
    private DriverContext driverContext;
    @Resource
    private DriverProperty driverProperty;
    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private PointClient pointClient;
    @Resource
    private DeviceClient deviceClient;
    @Resource
    private DriverClient driverClient;
    @Resource
    private ProfileClient profileClient;
    @Resource
    private PointInfoClient pointInfoClient;
    @Resource
    private DriverInfoClient driverInfoClient;
    @Resource
    private PointAttributeClient pointAttributeClient;
    @Resource
    private DriverAttributeClient driverAttributeClient;

    @Override
    public void initial() {
        // Register driver to dc3-manager, If it fails, try again 10 times
        int times = 1;
        while (!register()) {
            log.info("Retry {} times...", times);
            ThreadUtil.sleep(5, TimeUnit.SECONDS);

            times++;
            if (times > 10) {
                log.error("Driver registration failed");
                close();
            }
        }
        // Load profile, point, device, driver info, point info to context
        loadData();
    }

    @Override
    public void upsertProfile(Profile profile) {
        // Add profile driver info to context
        driverContext.getProfileDriverInfoMap().computeIfAbsent(profile.getId(), k -> new ConcurrentHashMap<>(16));

        // Add profile point to context
        driverContext.getProfilePointMap().computeIfAbsent(profile.getId(), k -> new ConcurrentHashMap<>(16));

        log.info("Upsert profile {}", profile);
    }

    @Override
    public void deleteProfile(Long id) {
        log.info("Delete profile {}, driverInfo {}, profilePoint {}", id, driverContext.getProfileDriverInfoMap().get(id), driverContext.getProfilePointMap().get(id));

        driverContext.getProfileDriverInfoMap().entrySet().removeIf(next -> next.getKey().equals(id));
        driverContext.getProfilePointMap().entrySet().removeIf(next -> next.getKey().equals(id));
    }

    @Override
    public void upsertDevice(Device device) {
        // Add device to context
        driverContext.getDeviceMap().put(device.getId(), device);
        // Add device name to context
        driverContext.getDeviceNameMap().put(device.getName(), device.getId());

        Map<Long, Map<String, AttributeInfo>> devicePointInfoMap = getDevicePointInfoMap(device);
        if (null != devicePointInfoMap) {
            // Add the device point info to context
            driverContext.getDevicePointInfoMap().put(device.getId(), devicePointInfoMap);
        }

        log.info("Upsert device {}, devicePointInfo {}", device, devicePointInfoMap);
    }

    @Override
    public void deleteDevice(Long id) {
        log.info("Delete device {}, devicePointInfo {}", driverContext.getDeviceMap().get(id), driverContext.getDevicePointInfoMap().get(id));

        driverContext.getDeviceMap().entrySet().removeIf(next -> next.getKey().equals(id));
        driverContext.getDeviceNameMap().entrySet().removeIf(next -> next.getValue().equals(id));
        driverContext.getDevicePointInfoMap().entrySet().removeIf(next -> next.getKey().equals(id));
    }

    @Override
    public void upsertPoint(Point point) {
        // Upsert point to profile point map context
        log.info("Upsert point {}", point);
        driverContext.getProfilePointMap().computeIfAbsent(point.getProfileId(), k -> new HashMap<>(16))
                .put(point.getId(), point);
    }

    @Override
    public void deletePoint(Long pointId, Long profileId) {
        // Delete point from profile point map context
        driverContext.getProfilePointMap().computeIfPresent(profileId, (k, v) -> {
            v.entrySet().removeIf(next -> {
                boolean equals = next.getKey().equals(pointId);
                if (equals) {
                    log.info("Delete point {}", next.getValue());
                }
                return equals;
            });
            return v;
        });
    }

    @Override
    public void upsertDriverInfo(DriverInfo driverInfo) {
        // Add driver info to driver info map context
        DriverAttribute attribute = this.driverAttributeMap.get(driverInfo.getDriverAttributeId());
        if (null != attribute) {
            log.info("Upsert driver info {}", driverInfo);
            driverContext.getProfileDriverInfoMap().computeIfAbsent(driverInfo.getProfileId(), k -> new HashMap<>(16))
                    .put(attribute.getName(), new AttributeInfo(driverInfo.getValue(), attribute.getType()));
        }
    }

    @Override
    public void deleteDriverInfo(Long attributeId, Long profileId) {
        DriverAttribute attribute = this.driverAttributeMap.get(attributeId);
        if (null != attribute) {
            String attributeName = attribute.getName();

            // Delete driver info from driver info map context
            driverContext.getProfileDriverInfoMap().computeIfPresent(profileId, (k, v) -> {
                v.entrySet().removeIf(next -> {
                    boolean equals = next.getKey().equals(attributeName);
                    if (equals) {
                        log.info("Delete driver info {}", next.getValue());
                    }
                    return equals;
                });
                return v;
            });
        }
    }

    @Override
    public void upsertPointInfo(PointInfo pointInfo) {
        // Add the point info to the device point info map context
        PointAttribute attribute = this.pointAttributeMap.get(pointInfo.getPointAttributeId());
        if (null != attribute) {
            log.info("Upsert point info {}", pointInfo);
            driverContext.getDevicePointInfoMap().computeIfAbsent(pointInfo.getDeviceId(), k -> new HashMap<>(16))
                    .computeIfAbsent(pointInfo.getPointId(), k -> new HashMap<>(16))
                    .put(attribute.getName(), new AttributeInfo(pointInfo.getValue(), attribute.getType()));
        }
    }

    @Override
    public void deletePointInfo(Long pointId, Long attributeId, Long deviceId) {
        PointAttribute attribute = this.pointAttributeMap.get(attributeId);
        if (null != attribute) {
            String attributeName = attribute.getName();

            // Delete the point info from the device info map context
            driverContext.getDevicePointInfoMap().computeIfPresent(deviceId, (k1, v1) -> {
                v1.computeIfPresent(pointId, (k2, v2) -> {
                    v2.entrySet().removeIf(next -> {
                        boolean equals = next.getKey().equals(attributeName);
                        if (equals) {
                            log.info("Delete point info {}", next.getValue());
                        }
                        return equals;
                    });
                    return v2;
                });
                return v1;
            });

            // If the point attribute is null, delete the point info from the device info map context
            driverContext.getDevicePointInfoMap().computeIfPresent(deviceId, (k, v) -> {
                v.entrySet().removeIf(next -> next.getValue().size() < 1);
                return v;
            });
        }
    }

    /**
     * register driver
     *
     * @return boolean
     */
    public boolean register() {
        log.info("Registering {} ", driverProperty.getName());
        if (!Dc3Util.isDriverPort(this.port)) {
            log.error("Invalid driver port, port range is 8600-8799");
            return false;
        }
        this.localHost = Dc3Util.localHost();
        if (!Dc3Util.isName(driverProperty.getName()) || !Dc3Util.isName(this.serviceName) || !Dc3Util.isHost(this.localHost)) {
            log.error("Driver Name || Driver Service Name || Driver Host is invalid");
            return false;
        }
        return registerDriver() && registerDriverAttribute() && registerPointAttribute();
    }

    /**
     * register driver
     * <p>
     * 驱动名称可以重复，但是驱动服务名称，主机IP和端口号不能重复
     *
     * @return boolean
     */
    public boolean registerDriver() {
        Driver driver = new Driver(driverProperty.getName(), this.serviceName, this.localHost, this.port);
        driver.setDescription(driverProperty.getDescription());

        R<Driver> rDriver = driverClient.selectByServiceName(this.serviceName);
        if (rDriver.isOk() && null != rDriver.getData()) {
            log.info("Driver already registered, updating {} ", driverProperty.getName());
            if (!driverProperty.getName().equals(rDriver.getData().getName())) {
                log.info("Change the driver({}) name to {}", rDriver.getData().getName(), driverProperty.getName());
            }
            driver.setId(rDriver.getData().getId());
            driverContext.setDriverId(driver.getId());
            return driverClient.update(driver).isOk();
        } else {
            log.info("Driver does not registered, adding {} ", driverProperty.getName());
            R<Driver> byHostPort = driverClient.selectByHostPort(this.localHost, this.port);
            if (byHostPort.isOk() && null != byHostPort.getData()) {
                log.error("The port({}) is already occupied by driver({}/{})", this.port, byHostPort.getData().getName(), byHostPort.getData().getServiceName());
                return false;
            }
            R<Driver> r = driverClient.add(driver);
            if (r.isOk()) {
                driverContext.setDriverId(r.getData().getId());
            }
            return r.isOk();
        }
    }

    /**
     * register driver attribute
     * <p>
     * 同步数据库和配置文件中的属性配置
     *
     * @return boolean
     */
    public boolean registerDriverAttribute() {
        Map<String, DriverAttribute> oldDriverAttributeMap = new HashMap<>(16);
        DriverAttributeDto connectInfoDto = new DriverAttributeDto(new Pages().setSize(-1L));
        connectInfoDto.setDriverId(driverContext.getDriverId());
        R<Page<DriverAttribute>> rDriverAttributes = driverAttributeClient.list(connectInfoDto);
        if (rDriverAttributes.isOk()) {
            rDriverAttributes.getData().getRecords().forEach(driverAttribute -> oldDriverAttributeMap.put(driverAttribute.getName(), driverAttribute));
        }

        Map<String, DriverAttribute> newDriverAttributeMap = new HashMap<>(16);
        Optional.ofNullable(driverProperty.getDriverAttribute()).ifPresent(driverAttributes -> {
            driverAttributes.forEach(driverAttribute -> newDriverAttributeMap.put(driverAttribute.getName(), driverAttribute));
        });

        for (String name : newDriverAttributeMap.keySet()) {
            DriverAttribute info = newDriverAttributeMap.get(name).setDriverId(driverContext.getDriverId());
            if (oldDriverAttributeMap.containsKey(name)) {
                info.setId(oldDriverAttributeMap.get(name).getId());
                log.info("Updating: {}", info);
                R<DriverAttribute> r = driverAttributeClient.update(info);
                if (!r.isOk()) {
                    log.error("The driver attribute({}) update failed", name);
                    return false;
                }
            } else {
                log.info("Adding: {}", info);
                R<DriverAttribute> r = driverAttributeClient.add(info);
                if (!r.isOk()) {
                    log.error("The driver attribute({}) add failed", name);
                    return false;
                }
            }
        }

        for (String name : oldDriverAttributeMap.keySet()) {
            if (!newDriverAttributeMap.containsKey(name)) {
                DriverInfoDto driverInfoDto = new DriverInfoDto();
                driverInfoDto.setPage(new Pages()).setDriverAttributeId(oldDriverAttributeMap.get(name).getId());
                R<Page<DriverInfo>> rDriverInfo = driverInfoClient.list(driverInfoDto);
                if (rDriverInfo.isOk() && rDriverInfo.getData().getRecords().size() > 0) {
                    log.error("The driver attribute({}) used by driver info and cannot be deleted", name);
                    return false;
                }
                log.info("Deleting: {}", oldDriverAttributeMap.get(name));
                R<Boolean> r = driverAttributeClient.delete(oldDriverAttributeMap.get(name).getId());
                if (!r.isOk()) {
                    log.error("The driver attribute({}) delete failed", name);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * register point attribute
     * <p>
     * 同步数据库和配置文件中的属性配置
     *
     * @return boolean
     */
    public boolean registerPointAttribute() {
        Map<String, PointAttribute> oldPointAttributeMap = new HashMap<>(16);
        PointAttributeDto pointAttributeDto = new PointAttributeDto();
        pointAttributeDto.setPage(new Pages().setSize(-1L)).setDriverId(driverContext.getDriverId());
        R<Page<PointAttribute>> list = pointAttributeClient.list(pointAttributeDto);
        if (list.isOk()) {
            list.getData().getRecords().forEach(pointAttribute -> oldPointAttributeMap.put(pointAttribute.getName(), pointAttribute));
        }

        Map<String, PointAttribute> newPointAttributeMap = new HashMap<>(16);
        Optional.ofNullable(driverProperty.getPointAttribute()).ifPresent(pointAttributes -> {
            pointAttributes.forEach(pointAttribute -> newPointAttributeMap.put(pointAttribute.getName(), pointAttribute));
        });

        for (String name : newPointAttributeMap.keySet()) {
            PointAttribute attribute = newPointAttributeMap.get(name).setDriverId(driverContext.getDriverId());
            if (oldPointAttributeMap.containsKey(name)) {
                attribute.setId(oldPointAttributeMap.get(name).getId());
                log.info("Updating: {}", attribute);
                R<PointAttribute> r = pointAttributeClient.update(attribute);
                if (!r.isOk()) {
                    log.error("The point attribute ({}) update failed", name);
                    return false;
                }
            } else {
                log.info("Adding: {}", attribute);
                R<PointAttribute> r = pointAttributeClient.add(attribute);
                if (!r.isOk()) {
                    log.error("The point attribute ({}) add failed", name);
                    return false;
                }
            }
        }

        for (String name : oldPointAttributeMap.keySet()) {
            if (!newPointAttributeMap.containsKey(name)) {
                PointInfoDto pointInfoDto = new PointInfoDto();
                pointInfoDto.setPage(new Pages()).setPointAttributeId(oldPointAttributeMap.get(name).getId());
                R<Page<PointInfo>> rPointInfo = pointInfoClient.list(pointInfoDto);
                if (rPointInfo.isOk() && rPointInfo.getData().getRecords().size() > 0) {
                    log.error("The point attribute({}) used by point info and cannot be deleted", name);
                    return false;
                }
                log.info("Deleting: {}", oldPointAttributeMap.get(name));
                R<Boolean> r = pointAttributeClient.delete(oldPointAttributeMap.get(name).getId());
                if (!r.isOk()) {
                    log.error("The point attribute ({}) delete failed", name);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * load data
     */
    public void loadData() {
        this.driverAttributeMap = loadDriverAttributeMap(driverContext.getDriverId());
        this.pointAttributeMap = loadPointAttributeMap(driverContext.getDriverId());

        List<Long> profileList = loadProfile(driverContext.getDriverId());
        driverContext.setProfileDriverInfoMap(loadDriverInfoMap(profileList));
        loadDevice(profileList);
        driverContext.setProfilePointMap(loadProfilePointMap(profileList));
        loadDevicePointMap(driverContext.getDeviceMap());
    }

    /**
     * 获取驱动配置属性 Map
     * driverAttributeId,driverAttribute
     *
     * @param driverId Driver Id
     * @return Map
     */
    public Map<Long, DriverAttribute> loadDriverAttributeMap(long driverId) {
        log.info("Load driver attribute into memory");
        Map<Long, DriverAttribute> driverAttributeMap = new ConcurrentHashMap<>(16);
        DriverAttributeDto driverAttributeDto = new DriverAttributeDto();
        driverAttributeDto.setPage(new Pages().setSize(-1L)).setDriverId(driverId);
        R<Page<DriverAttribute>> rDriverAttribute = driverAttributeClient.list(driverAttributeDto);
        if (!rDriverAttribute.isOk()) {
            close();
            throw new ServiceException(rDriverAttribute.getMessage());
        }
        rDriverAttribute.getData().getRecords().forEach(driverAttribute -> driverAttributeMap.put(driverAttribute.getId(), driverAttribute));
        return driverAttributeMap;
    }

    /**
     * 获取位号配置属性 Map
     * pointAttributeId,pointAttribute
     *
     * @param driverId Driver Id
     * @return Map
     */
    public Map<Long, PointAttribute> loadPointAttributeMap(long driverId) {
        log.info("Load point attribute into memory");
        Map<Long, PointAttribute> pointAttributeMap = new ConcurrentHashMap<>(16);
        PointAttributeDto pointAttributeDto = new PointAttributeDto();
        pointAttributeDto.setPage(new Pages().setSize(-1L)).setDriverId(driverId);
        R<Page<PointAttribute>> rPointAttribute = pointAttributeClient.list(pointAttributeDto);
        if (!rPointAttribute.isOk()) {
            close();
            throw new ServiceException(rPointAttribute.getMessage());
        }
        rPointAttribute.getData().getRecords().forEach(pointAttribute -> pointAttributeMap.put(pointAttribute.getId(), pointAttribute));
        return pointAttributeMap;
    }

    /**
     * load driver profile
     *
     * @param driverId Driver Id
     * @return Array
     */
    public List<Long> loadProfile(long driverId) {
        log.info("Load profile into memory");
        List<Long> profileList = new ArrayList<>();
        ProfileDto profileDto = new ProfileDto();
        profileDto.setPage(new Pages().setSize(-1L)).setDriverId(driverId);
        R<Page<Profile>> rp = profileClient.list(profileDto);
        if (!rp.isOk()) {
            close();
            throw new ServiceException(rp.getMessage());
        }
        rp.getData().getRecords().forEach(profile -> profileList.add(profile.getId()));
        return profileList;
    }

    /**
     * 获取模板驱动配置信息 Map
     * profileId(driverAttribute.name,(drverInfo.value,driverAttribute.type))
     *
     * @param profileList Profile Array
     * @return Map
     */
    public Map<Long, Map<String, AttributeInfo>> loadDriverInfoMap(List<Long> profileList) {
        log.info("Load driver info into memory");
        Map<Long, Map<String, AttributeInfo>> driverInfoMap = new HashMap<>(16);
        for (Long profileId : profileList) {
            Map<String, AttributeInfo> infoMap = getDriverInfoMap(profileId);
            if (infoMap.size() > 0) {
                driverInfoMap.put(profileId, infoMap);
            }
        }
        return driverInfoMap;
    }

    /**
     * 初始化设备
     *
     * @param profileList Profile Array
     */
    public void loadDevice(List<Long> profileList) {
        log.info("Load device into memory");
        driverContext.setDeviceMap(new HashMap<>(16));
        driverContext.setDeviceNameMap(new HashMap<>(16));
        for (Long profileId : profileList) {
            DeviceDto deviceDto = new DeviceDto();
            deviceDto.setPage(new Pages().setSize(-1L)).setProfileId(profileId);
            R<Page<Device>> rp = deviceClient.list(deviceDto);
            if (!rp.isOk()) {
                close();
                throw new ServiceException(rp.getMessage());
            }
            for (Device device : rp.getData().getRecords()) {
                driverContext.getDeviceMap().put(device.getId(), device);
                driverContext.getDeviceNameMap().put(device.getName(), device.getId());
            }
        }
    }

    /**
     * 获取模板位号 Map
     * profileId(pointId,point)
     *
     * @param profileList Profile Array
     * @return Map
     */
    public Map<Long, Map<Long, Point>> loadProfilePointMap(List<Long> profileList) {
        log.info("Load profile point into memory");
        Map<Long, Map<Long, Point>> pointMap = new HashMap<>(16);
        for (Long profileId : profileList) {
            pointMap.put(profileId, getPointMap(profileId));
        }
        return pointMap;
    }

    /**
     * 初始化位号
     * deviceId(pointId(pointAttribute.name,(pointInfo.value,pointAttribute.type)))
     *
     * @param deviceMap Device Map
     */
    public void loadDevicePointMap(Map<Long, Device> deviceMap) {
        log.info("Load device point into memory");
        driverContext.setDevicePointInfoMap(new HashMap<>(16));
        driverContext.setDevicePointNameMap(new HashMap<>(16));
        for (Device device : deviceMap.values()) {
            Map<Long, Map<String, AttributeInfo>> infoMap = getDevicePointInfoMap(device);
            if (infoMap.size() > 0) {
                driverContext.getDevicePointInfoMap().put(device.getId(), infoMap);
            }
            Map<String, Long> nameMap = getPointNameMap(device.getProfileId());
            if (nameMap.size() > 0) {
                driverContext.getDevicePointNameMap().put(device.getId(), nameMap);
            }
        }
    }

    /**
     * Get driver info map, return map(attributeName,attributeInfo(value,type))
     *
     * @param profileId Profile Id
     * @return Map
     */
    public Map<String, AttributeInfo> getDriverInfoMap(Long profileId) {
        Map<String, AttributeInfo> attributeInfoMap = new HashMap<>(16);

        // Get driver info list from dc3-manager service
        DriverInfoDto driverInfoDto = new DriverInfoDto(new Pages().setSize(-1L));
        driverInfoDto.setProfileId(profileId);
        R<Page<DriverInfo>> rp = driverInfoClient.list(driverInfoDto);
        if (!rp.isOk()) {
            log.error("Get driver info map failed {}", rp.getMessage());
            return null;
        }

        for (DriverInfo driverInfo : rp.getData().getRecords()) {
            DriverAttribute attribute = this.driverAttributeMap.get(driverInfo.getDriverAttributeId());
            attributeInfoMap.put(attribute.getName(), new AttributeInfo(driverInfo.getValue(), attribute.getType()));
        }

        return attributeInfoMap;
    }

    /**
     * Get point info map, return map(pointId,attribute(attributeName,attributeInfo(value,type)))
     *
     * @param device Device
     * @return Map
     */
    public Map<Long, Map<String, AttributeInfo>> getDevicePointInfoMap(Device device) {
        Map<Long, Map<String, AttributeInfo>> attributeInfoMap = new HashMap<>(16);

        Map<Long, Point> pointMap = driverContext.getProfilePointMap().get(device.getProfileId());
        for (Long pointId : pointMap.keySet()) {
            // Get point info list from dc3-manager service
            PointInfoDto pointInfoDto = new PointInfoDto(new Pages().setSize(-1L));
            pointInfoDto.setDeviceId(device.getId()).setPointId(pointId);
            R<Page<PointInfo>> rp = pointInfoClient.list(pointInfoDto);
            if (!rp.isOk()) {
                log.error("Get point info map failed {}", rp.getMessage());
                return null;
            }

            Map<String, AttributeInfo> infoMap = new HashMap<>(16);
            List<PointInfo> pointInfos = rp.getData().getRecords();
            for (PointInfo pointInfo : pointInfos) {
                PointAttribute attribute = this.pointAttributeMap.get(pointInfo.getPointAttributeId());
                infoMap.put(attribute.getName(), new AttributeInfo(pointInfo.getValue(), attribute.getType()));
            }
            if (infoMap.size() > 0) {
                attributeInfoMap.put(pointId, infoMap);
            }
        }
        return attributeInfoMap;
    }

    /**
     * Get point name map, return map(pointName,pointId)
     *
     * @param profileId Profile Id
     * @return Map
     */
    public Map<String, Long> getPointNameMap(Long profileId) {
        Map<String, Long> pointNameMap = new HashMap<>(16);

        Map<Long, Point> pointMap = driverContext.getProfilePointMap().get(profileId);
        for (Point point : pointMap.values()) {
            pointNameMap.put(point.getName(), point.getId());
        }

        return pointNameMap;
    }

    /**
     * Get point map, return map(pointId,point)
     *
     * @param profileId Profile Id
     * @return Map
     */
    public Map<Long, Point> getPointMap(Long profileId) {
        Map<Long, Point> pointMap = new HashMap<>(16);

        // Get point list from dc3-manager service
        PointDto pointDto = new PointDto(new Pages().setSize(-1L));
        pointDto.setProfileId(profileId);
        R<Page<Point>> rp = pointClient.list(pointDto);
        if (!rp.isOk()) {
            log.error("Get point map failed {}", rp.getMessage());
            return null;
        }

        for (Point point : rp.getData().getRecords()) {
            pointMap.put(point.getId(), point);
        }

        return pointMap;
    }

    /**
     * Close ApplicationContext
     */
    private void close() {
        ((ConfigurableApplicationContext) applicationContext).close();
    }
}
