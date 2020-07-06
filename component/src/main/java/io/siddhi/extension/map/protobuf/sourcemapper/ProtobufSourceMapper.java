/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.siddhi.extension.map.protobuf.sourcemapper;

import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.MapField;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.event.Event;
import io.siddhi.core.exception.MappingFailedException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.stream.input.source.AttributeMapping;
import io.siddhi.core.stream.input.source.InputEventHandler;
import io.siddhi.core.stream.input.source.SourceMapper;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.error.handler.model.ErroneousEvent;
import io.siddhi.core.util.transport.OptionHolder;
import io.siddhi.extension.map.protobuf.utils.ProtobufConstants;
import io.siddhi.extension.map.protobuf.utils.ProtobufUtils;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.StreamDefinition;
import io.siddhi.query.api.exception.SiddhiAppValidationException;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getMethodName;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getRPCmethodList;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getServiceName;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.protobufFieldsWithTypes;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.removeUnderscore;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.toUpperCamelCase;

/**
 * Protobuf SinkMapper converts protobuf message objects in to siddhi events.
 */
@Extension(
        name = "protobuf",
        namespace = "sourceMapper",
        description = "" +
                "This input mapper allows you to convert protobuf messages into Events. To work with this input " +
                "mapper you have to add auto-generated protobuf classes to the project classpath. When you use this " +
                "input mapper, you can either define stream attributes as the same names as the protobuf message " +
                "attributes or you can use custom mapping to map stream definition attributes with the protobuf " +
                "attributes.Please find the sample proto definition [here](https://github.com/siddhi-io/siddhi-map-" +
                "protobuf/tree/master/component/src/main/resources/sample.proto). When you use this " +
                "mapper with `siddhi-io-grpc` you don't have to provide the protobuf message class in the `class`" +
                " parameter.  ",
        parameters = {
                @Parameter(name = "class",
                        description = "" +
                                "This specifies the class name of the protobuf message class, If sink type is grpc " +
                                "then it's not necessary to provide this field.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "-"),
        },
        examples = {
                @Example(
                        syntax = "@source(type='inMemory', topic='test01', \n" +
                                "@map(type='protobuf', class='io.siddhi.extension.map.protobuf.autogenerated" +
                                ".Request')) \n" +
                                "define stream FooStream (stringValue string, intValue int,longValue long," +
                                "booleanValue bool,floatValue float,doubleValue double); \n"
                        ,
                        description = "" +
                                "This will convert the `io.siddhi.extension.map.protobuf.autogenerated.Request` " +
                                "protobuf messages into siddhi events."
                ),
                @Example(
                        syntax = "source(type='grpc', receiver.url = 'grpc://localhost:8084/org.wso2.grpc.test." +
                                "MyService/process', \n" +
                                "@map(type='protobuf')) " +
                                "define stream FooStream (stringValue string, intValue int,longValue long," +
                                "booleanValue bool,floatValue float,doubleValue double); ",
                        description = "" +
                                "This will convert the protobuf messages that are received to this" +
                                " source into siddhi events. Since this is `grpc` source we don't need to provide " +
                                "the `class` parameter"
                ),
                @Example(
                        syntax = "source(type='grpc', receiver.url = 'grpc://localhost:8084/org.wso2.grpc.test." +
                                "MyService/process', \n" +
                                "@map(type='protobuf', " +
                                "@attributes(a = 'stringValue', b = 'intValue', c = 'longValue',d = 'booleanValue'," +
                                "' e = floatValue', f ='doubleValue'))) \n" +
                                "define stream FooStream (a string ,c long,b int, d bool,e float,f double);",
                        description = "" +
                                "This will convert the protobuf messages that are received to this" +
                                " source into siddhi events. since there's a mapping available for the stream, " +
                                "protobuf message object will be map like this, \n" +
                                "- `stringValue` of the protobuf message will be assign to the `a` attribute of the " +
                                "stream \n" +
                                "- `intValue` of the protobuf message will be assign to the `b` attribute of the " +
                                "stream \n" +
                                "- `longValue` of the protobuf message will be assign to the `c` attribute of the " +
                                "stream \n" +
                                "- `booleanValue` of the protobuf message will be assign to the `d` attribute of the " +
                                "stream \n" +
                                "- `floatValue` of the protobuf message will be assign to the `e` attribute of the " +
                                "stream \n" +
                                "- `doubleValue` of the protobuf message will be assign to the `f` attribute of the " +
                                "stream \n"
                ),
                @Example(
                        syntax = "source((type='inMemory', topic='test01', \n" +
                                "@map(type='protobuf', class='io.siddhi.extension.map.protobuf.autogenerated." +
                                "RequestWithList)) \n" +
                                "define stream FooStream (stringValue string ,intValue int,stringList object, " +
                                "intList object););",
                        description = "" +
                                "This will convert the `io.siddhi.extension.map.protobuf.autogenerated." +
                                "RequestWithList` protobuf messages that are received to this" +
                                " source into siddhi events. If you want to map data types other than " +
                                "the scalar data types, you have to use `object` as the data type as shown in " +
                                "above(`stringList object`)"
                )
        }
)
public class ProtobufSourceMapper extends SourceMapper {
    private static final Logger log = Logger.getLogger(ProtobufSourceMapper.class);
    private List<MappingPositionData> mappingPositionDataList;
    private Object messageBuilderObject;
    private int size;
    private String siddhiAppName;
    private String streamID;
    private Class messageObjectClass;

    @Override
    public void init(StreamDefinition streamDefinition, OptionHolder optionHolder,
                     List<AttributeMapping> attributeMappingList, ConfigReader configReader,
                     SiddhiAppContext siddhiAppContext) {

        mappingPositionDataList = new ArrayList<>();
        this.size = streamDefinition.getAttributeList().size();
        this.siddhiAppName = siddhiAppContext.getName();
        this.streamID = streamDefinition.getId();
        String userProvidedClassName = null;
        if (optionHolder.isOptionExists(ProtobufConstants.CLASS_OPTION_HOLDER)) {
            userProvidedClassName = optionHolder.validateAndGetOption(ProtobufConstants.CLASS_OPTION_HOLDER).getValue();
        }

        if (sourceType.toLowerCase().startsWith(ProtobufConstants.GRPC_PROTOCOL_NAME)) {
            if (ProtobufConstants.GRPC_SERVICE_SOURCE_NAME.equalsIgnoreCase(sourceType)
                    && attributeMappingList.size() == 0) {
                throw new SiddhiAppCreationException("No mapping found at @Map, mapping should be available to " +
                        "continue for Siddhi App " + siddhiAppName); //grpc-service should have a mapping
            }
            String url = null;
            if (sourceOptionHolder.isOptionExists(ProtobufConstants.RECEIVER_URL)) {
                url = sourceOptionHolder.validateAndGetStaticValue(ProtobufConstants.RECEIVER_URL);
            } else if (sourceType.equalsIgnoreCase(ProtobufConstants.GRPC_CALL_RESPONSE_SOURCE_NAME)) {
                String sinkId = sourceOptionHolder.validateAndGetStaticValue(ProtobufConstants.SINK_ID);
                url = ProtobufUtils.getURL(siddhiAppContext, "sink",
                        ProtobufConstants.GRPC_CALL_SINK_NAME,
                        ProtobufConstants.PUBLISHER_URL, ProtobufConstants.SINK_ID, sinkId);
            }
            if (url != null) {
                URL aURL;
                try {
                    if (!url.toLowerCase().startsWith(ProtobufConstants.GRPC_PROTOCOL_NAME)) {
                        throw new SiddhiAppValidationException(siddhiAppName + ":" + streamID + ": The url must " +
                                "begin with \"" + ProtobufConstants.GRPC_PROTOCOL_NAME + "\" for all grpc sinks");
                    }
                    aURL = new URL(ProtobufConstants.DUMMY_PROTOCOL_NAME + url.substring(4));
                } catch (MalformedURLException e) {
                    throw new SiddhiAppValidationException(siddhiAppName + ":" + streamID + ": Error in URL format. " +
                            "Expected format is `grpc://0.0.0.0:9763/<serviceName>/<methodName>` but the provided url" +
                            " is " + url + "," + e.getMessage(), e);
                }
                String methodReference = getMethodName(aURL.getPath(), siddhiAppName, streamID);
                String fullQualifiedServiceReference = getServiceName(aURL.getPath(), siddhiAppName, siddhiAppName);
                try {
                    String capitalizedFirstLetterMethodName = methodReference.substring(0, 1).toUpperCase() +
                            methodReference.substring(1);
                    Field methodDescriptor = Class.forName(fullQualifiedServiceReference +
                            ProtobufConstants.GRPC_PROTOCOL_NAME_UPPERCAMELCASE).getDeclaredField
                            (ProtobufConstants.GETTER + capitalizedFirstLetterMethodName +
                                    ProtobufConstants.METHOD_NAME);
                    ParameterizedType parameterizedType = (ParameterizedType) methodDescriptor.getGenericType();
                    if (ProtobufConstants.GRPC_CALL_RESPONSE_SOURCE_NAME.equalsIgnoreCase(sourceType)) {
                        messageObjectClass = (Class) parameterizedType
                                .getActualTypeArguments()[ProtobufConstants.RESPONSE_CLASS_POSITION];
                    } else {
                        messageObjectClass = (Class) parameterizedType
                                .getActualTypeArguments()[ProtobufConstants.REQUEST_CLASS_POSITION];
                    }
                    if (userProvidedClassName != null) {
                        if (url.toLowerCase().startsWith(ProtobufConstants.GRPC_PROTOCOL_NAME)) {
                            /* only if sink is a grpc type, check for both user provided class name and the required
                             class name*/
                            if (!messageObjectClass.getName().equals(userProvidedClassName)) {
                                throw new SiddhiAppCreationException(siddhiAppName + ":" + streamID +
                                        ": provided class name does not match with the original mapping class, " +
                                        "provided class: '" + userProvidedClassName + "', expected: '" +
                                        messageObjectClass.getName() + "'.");
                            }
                        }
                    }
                    Method builderMethod = messageObjectClass.getDeclaredMethod(ProtobufConstants.NEW_BUILDER_NAME);
                    //to create an builder object of message class
                    messageBuilderObject = builderMethod.invoke(messageObjectClass); // create the object
                } catch (ClassNotFoundException e) {
                    throw new SiddhiAppCreationException(siddhiAppName + ":" + streamID + ": " +
                            "Invalid service name provided in url, provided service name : '" +
                            fullQualifiedServiceReference + "'," + e.getMessage(), e);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                        NoSuchFieldException e) {
                    //same error as NoSuchMethod', because field is getting from the method name
                    throw new SiddhiAppCreationException(siddhiAppName + ":" + streamID + ": Invalid method name " +
                            "provided in the url, provided method name : '" + methodReference + "' expected one of " +
                            "these methods : " + getRPCmethodList(fullQualifiedServiceReference, siddhiAppName,
                            streamID) + "," + e.getMessage(), e);
                }
            } else {
                throw new SiddhiAppValidationException(siddhiAppName + ":" + streamID + ": either " +
                        "receiver.url or publisher.url should be given. But found neither");
            }
        } else {
            log.debug(siddhiAppName + ":" + streamID + ": Not a grpc source, getting the protobuf class name from " +
                    "'class' parameter");
            if (userProvidedClassName == null) {
                throw new SiddhiAppCreationException(siddhiAppName + ":" + streamID + "No class name provided in the " +
                        "@map, you should provide the class name with the 'class' parameter");
            }
            try {
                messageObjectClass = Class.forName(userProvidedClassName);
                Method builderMethod = messageObjectClass.getDeclaredMethod(ProtobufConstants.NEW_BUILDER_NAME); //to
                // create an builder object of message class
                messageBuilderObject = builderMethod.invoke(messageObjectClass); // create the  builder object
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                    InvocationTargetException e) {
                throw new SiddhiAppCreationException(siddhiAppName + ":" + streamID + ": Invalid class name provided " +
                        "in the 'class' parameter, provided class name: " + userProvidedClassName + "," +
                        e.getMessage(), e);
            }
        }
        initializeGetterMethods(streamDefinition, messageObjectClass, attributeMappingList);
    }


    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{GeneratedMessageV3.class};
    }

    @Override
    protected void mapAndProcess(Object eventObject, InputEventHandler inputEventHandler)
            throws InterruptedException, MappingFailedException {
        List<ErroneousEvent> failedEvents = new ArrayList<>(0);
        Object[] objectArray = new Object[this.size];
        boolean malformedEvent = false;
        if (!sourceType.toLowerCase().startsWith(ProtobufConstants.GRPC_PROTOCOL_NAME)) {
            try {
                eventObject = messageObjectClass.getDeclaredMethod(ProtobufConstants.PARSE_FROM_NAME, byte[].class)
                        .invoke(messageObjectClass, eventObject);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                String errStr = siddhiAppName + ":" + streamID + " error while creating the " +
                        "protobuf message in " + sourceType + " source, expect the protobuf message as a byte array, "
                        + e.getMessage();
                failedEvents.add(new ErroneousEvent(eventObject, e, errStr));
                log.error(errStr, e);
                malformedEvent = true;
            }
        }
        for (MappingPositionData mappingPositionData : mappingPositionDataList) {
            Object value;
            try {
                value = mappingPositionData.getGetterMethod().invoke(eventObject);
                objectArray[mappingPositionData.getPosition()] = value;
            } catch (IllegalAccessException | InvocationTargetException e) {
                String errStr = siddhiAppName + ":" + streamID + " Unknown error occurred during " +
                        "runtime," + e.getMessage();
                failedEvents.add(new ErroneousEvent(eventObject, e, errStr));
                log.error(errStr, e);
                malformedEvent = true;
            } // this error will not throw, All possible scenarios are handled in the init() method.
        }
        if (!malformedEvent) {
            Event event = new Event();
            event.setData(objectArray);
            inputEventHandler.sendEvent(event);
        }
        if (!failedEvents.isEmpty()) {
            throw new MappingFailedException(failedEvents);
        }
    }

    @Override
    protected boolean allowNullInTransportProperties() {
        return false;
    }

    private void initializeGetterMethods(StreamDefinition streamDefinition, Class messageObjectClass,
                                         List<AttributeMapping> attributeMappingList) {
        String attributeName = null;
        try {
            if (attributeMappingList.size() == 0) { //if no mapping is available
                for (int i = 0; i < streamDefinition.getAttributeList().size(); i++) {
                    Attribute attribute = streamDefinition.getAttributeList().get(i);
                    attributeName = attribute.getName();
                    Attribute.Type attributeType = attribute.getType();
                    Method getter = getGetterMethod(attributeType, attributeName);
                    mappingPositionDataList.add(new MappingPositionData(i, getter));
                }
            } else {
                for (int i = 0; i < attributeMappingList.size(); i++) {
                    AttributeMapping attributeMapping = attributeMappingList.get(i);
                    attributeName = attributeMapping.getMapping();
                    int position = attributeMapping.getPosition();
                    Attribute.Type attributeType = streamDefinition.getAttributeList().get(i).getType();
                    Method getter = getGetterMethod(attributeType, attributeName);
                    mappingPositionDataList.add(new MappingPositionData(position, getter));
                }
            }
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            Field[] fields = messageBuilderObject.getClass().getDeclaredFields();
            throw new SiddhiAppRuntimeException(siddhiAppName + ":" + streamID + "Attribute name or type do " +
                    "not match with protobuf variable or type. provided attribute '" + attributeName +
                    "'. Expected one of these attributes " + protobufFieldsWithTypes(fields) + ".", e);
        }
    }

    private Method getGetterMethod(Attribute.Type attributeType, String attributeName)
            throws NoSuchMethodException, NoSuchFieldException {
        attributeName = removeUnderscore(attributeName);
        if (attributeType == Attribute.Type.OBJECT) {
            if (List.class.isAssignableFrom(messageObjectClass.getDeclaredField(attributeName +
                    ProtobufConstants.UNDERSCORE)
                    .getType())) {
                return messageObjectClass.getDeclaredMethod(
                        ProtobufConstants.GETTER + toUpperCamelCase(attributeName) + ProtobufConstants.LIST_NAME);
            } else if (MapField.class.isAssignableFrom(messageObjectClass.getDeclaredField(attributeName +
                    ProtobufConstants.UNDERSCORE)
                    .getType())) {
                return messageObjectClass.getDeclaredMethod(
                        ProtobufConstants.GETTER + toUpperCamelCase(attributeName) + ProtobufConstants.MAP_NAME);
            } else if (GeneratedMessageV3.class.isAssignableFrom(messageObjectClass.getDeclaredField(
                    attributeName + ProtobufConstants.UNDERSCORE)
                    .getType())) {
                return messageObjectClass.getDeclaredMethod(ProtobufConstants.GETTER + toUpperCamelCase(
                        attributeName));
            } else {
                throw new SiddhiAppCreationException("Unknown data type. You should provide either 'map' , 'list' or" +
                        " 'another message type' with 'object' data type");
            }
        } else {
            return messageObjectClass.getDeclaredMethod(ProtobufConstants.GETTER + toUpperCamelCase(
                    attributeName));
        }
    }

    private static class MappingPositionData {
        private int position;
        private Method getterMethod;

        private MappingPositionData(int position, Method getterMethod) {
            this.position = position;
            this.getterMethod = getterMethod;
        }

        private int getPosition() {
            return position;
        }

        private Method getGetterMethod() {
            return getterMethod;
        }
    }

}
