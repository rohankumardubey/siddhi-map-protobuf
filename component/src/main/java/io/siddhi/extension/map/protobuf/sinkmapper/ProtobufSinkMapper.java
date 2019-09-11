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
package io.siddhi.extension.map.protobuf.sinkmapper;

import com.google.protobuf.GeneratedMessageV3;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.event.Event;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.stream.output.sink.SinkListener;
import io.siddhi.core.stream.output.sink.SinkMapper;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.transport.OptionHolder;
import io.siddhi.core.util.transport.TemplateBuilder;
import io.siddhi.extension.map.protobuf.utils.GrpcConstants;
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
import java.util.Map;

import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getMethodName;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getRPCmethodList;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.getServiceName;
import static io.siddhi.extension.map.protobuf.utils.ProtobufUtils.protobufFieldsWithTypes;

/**
 * Protobuf SinkMapper converts siddhi events in to protobuf message objects.
 */
@Extension(
        name = "protobuf",
        namespace = "sinkMapper",

        description = "" +
                "This extension is an Event to Protobuf Message Object." +
                "You have to add autogenerated protobuf message classes and service classes to the project classpath " +
                "in order to work with this mapper. Once you add them to the classpath you can convert Siddhi Event " +
                "objects to protobuf message object.\n" +
                "If you named your stream values as same as the protobuf message definition " +
                "(also in the same order) this mapper will " +
                "automatically convert Siddhi Event to protobuf message type, Otherwise you have to use @payload to " +
                "map your stream values with protobuf message object(Example 2). You can even use protobuf Maps " +
                "with this mapper, In order to use maps you should use  HashMap " +
                ", LinkedHashMap or TreeMap and pass them directly to the stream as an object (Example 3).Please find" +
                " the sample proto definition [here](https://github.com/siddhi-io/siddhi-io/siddhi-map-protobuf/" +
                "tree/master/component/src/main/resources/) " +
                "   ",
        parameters = {
                @Parameter(name = "class",
                        description = "" +
                                "This specifies the class name of the protobuf message class, If sink type is grpc " +
                                "then it's not necessary to provide this parameter.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = " "),
        },
        examples = {
                @Example(
                        syntax = "@sink(type='grpc',  url = 'grpc://localhost:2000/org.wso2.grpc.test" +
                                ".MyService/process \n" +
                                "@map(type='protobuf')) \n" +
                                "define stream BarStream (stringValue string, intValue int,longValue long," +
                                "booleanValue bool,floatValue float,doubleValue double)",
                        description = "Above definition will map BarStream values into the protobuf message type of " +
                                "the 'process' method in 'MyService' service"),

                @Example(
                        syntax = "@sink(type='grpc', url = 'grpc://localhost:2000/org.wso2.grpc.test" +
                                ".MyService/process\n" +
                                "@map(type='protobuf'), \n" +
                                "@payload(stringValue='a',longValue='b',intValue='c',booleanValue='d',floatValue = " +
                                "'e', doubleValue  = 'f'))) \n" +
                                "define stream BarStream (a string, b long, c int,d bool,e float,f double);",

                        description = "The above definition will map BarStream values to request message type of the " +
                                "'process' method in 'MyService' service. and stream values will map like this, \n" +
                                "- value of 'a' will be assign 'stringValue' variable in the message class \n" +
                                "- value of 'b' will be assign 'longValue' variable in the message class \n" +
                                "- value of 'c' will be assign 'intValue' variable in the message class \n" +
                                "- value of 'd' will be assign 'booleanValue' variable in the message class \n" +
                                "- value of 'e' will be assign 'floatValue' variable in the message class \n" +
                                "- value of 'f' will be assign 'doubleValue' variable in the message class \n" +
                                ""
                ),
                @Example(
                        syntax = "@sink(type='grpc', url = 'grpc://localhost:2000/org.wso2.grpc.test" +
                                ".MyService/testMap' \n" +
                                "@map(type='protobuf')) \n" +
                                " define stream BarStream (stringValue string,intValue int,map object);",

                        description = "The above definition will map BarStream values to request message type of the " +
                                "'testMap' method in 'MyService' service and since there is an object data type is in" +
                                "the stream(map object) , mapper will assume that 'map' is an instance of  " +
                                "'java.util.Map' class, otherwise it will throws and error. \n" +
                                ""

                )
        }
)

public class ProtobufSinkMapper extends SinkMapper {
    // TODO: 9/11/19 Add examples
    private static final Logger log = Logger.getLogger(ProtobufSinkMapper.class);
//    private Class builder;
    private Object messageBuilderObject;
    private List<MappingPositionData> mappingPositionDataList;
    private String siddhiAppName;

    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[0];
    }

    @Override
    public void init(StreamDefinition streamDefinition, OptionHolder optionHolder, Map<String, TemplateBuilder>
            templateBuilderMap, ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        this.siddhiAppName = siddhiAppContext.getName();
        if (GrpcConstants.GRPC_SERVICE_RESPONSE_SINK_NAME.equalsIgnoreCase(sinkType) // TODO: 9/11/19 skip this
                && templateBuilderMap.size() == 0) { // TODO: 9/11/19 change the message
            throw new SiddhiAppCreationException(" No mapping found at @Map, mapping is required to continue " +
                    "for Siddhi App " + siddhiAppName); //grpc-service-response should have a mapping
        }
        mappingPositionDataList = new ArrayList<>();
        String url = null;
        try {
            url = sinkOptionHolder.validateAndGetStaticValue(GrpcConstants.PUBLISHER_URL);
        } catch (SiddhiAppValidationException ignored) {
            log.info("Url is not provided, getting the class name from the 'class' parameter");
        }
        String userProvidedClassName = null; //todo change into null. - done
        if (optionHolder.isOptionExists(GrpcConstants.CLASS_OPTION_HOLDER)) {
            userProvidedClassName = optionHolder.validateAndGetOption(GrpcConstants.CLASS_OPTION_HOLDER).getValue();
        }
        Class messageObjectClass;
        if (url != null) {
            URL aURL;
            try { // TODO: 9/11/19 Check for grpc - done
                if (!url.startsWith(GrpcConstants.GRPC_PROTOCOL_NAME)) {
                    throw new SiddhiAppValidationException(siddhiAppContext.getName() + " : The url must " +
                            "begin with \"" + GrpcConstants.GRPC_PROTOCOL_NAME + "\" for all grpc sinks");
                }
                aURL = new URL(GrpcConstants.DUMMY_PROTOCOL_NAME + url.substring(4));
            } catch (MalformedURLException e) {
                throw new SiddhiAppValidationException(siddhiAppContext.getName() + ": Error in URL format. Expected " +
                        "format is `grpc://0.0.0.0:9763/<serviceName>/<methodName>` but the provided url is " + url + "," + e.getMessage()
                        , e); // TODO: 9/11/19 add e.getmessage() - done
            }
            String methodReference = getMethodName(aURL.getPath());
            String fullQualifiedServiceReference = getServiceName(aURL.getPath());
            //if user provides the class parameter inside the @templateBuilderMap
            try {
                String capitalizedFirstLetterMethodName = methodReference.substring(0, 1).toUpperCase() +
                        methodReference.substring(1);
                Field methodDescriptor = Class.forName(fullQualifiedServiceReference
                        + GrpcConstants.GRPC_PROTOCOL_NAME_UPPERCAMELCASE).getDeclaredField
                        (GrpcConstants.GETTER + capitalizedFirstLetterMethodName + GrpcConstants.METHOD_NAME);
                ParameterizedType parameterizedType = (ParameterizedType) methodDescriptor.getGenericType();
                if (GrpcConstants.GRPC_SERVICE_RESPONSE_SINK_NAME.equalsIgnoreCase(sinkType)) {
                    messageObjectClass = (Class) parameterizedType.
                            getActualTypeArguments()[GrpcConstants.RESPONSE_CLASS_POSITION];
                } else {
                    messageObjectClass = (Class) parameterizedType.
                            getActualTypeArguments()[GrpcConstants.REQUEST_CLASS_POSITION];
                }
                if (userProvidedClassName != null) { //todo change the testcases
                    if (url.startsWith(GrpcConstants.GRPC_PROTOCOL_NAME)) { // only if sink is a grpc type, check for
                        // both user provided class name and the required class name
                        if (!messageObjectClass.getName().equals(userProvidedClassName)) {
                            throw new SiddhiAppCreationException(siddhiAppContext.getName() +
                                    " : provided class name does not match with the original mapping class, provided " +
                                    "class : " + userProvidedClassName + " , expected : " + messageObjectClass.getName());
                        }
                    }
                }
                Method builderMethod = messageObjectClass.getDeclaredMethod(GrpcConstants.NEW_BUILDER_NAME); //to
                // create an builder object of message class
                messageBuilderObject = builderMethod.invoke(messageObjectClass); // create the object
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NoSuchFieldException e) {
                throw new SiddhiAppCreationException(siddhiAppName + ": Invalid method name provided " +
                        "in the url, provided method name : '" + methodReference + "' expected one of these methods :" +
                        " " +
                        getRPCmethodList(fullQualifiedServiceReference, siddhiAppName) + "," + e.getMessage(), e);//
                // TODO: 9/11/19 pass e -done
            } catch (ClassNotFoundException e) {
                throw new SiddhiAppCreationException(siddhiAppName + " : Invalid service name provided in" +
                        " url, provided service name : '" + fullQualifiedServiceReference + "'," + e.getMessage(), e);
            }

        } else {
            try {
                messageObjectClass = Class.forName(userProvidedClassName);
                Method builderMethod = messageObjectClass.getDeclaredMethod(GrpcConstants.NEW_BUILDER_NAME); //to
                // create an builder object of message class
                messageBuilderObject = builderMethod.invoke(messageObjectClass); // create the  builder object
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new SiddhiAppCreationException(siddhiAppName + " : Invalid class name provided in the 'class'" +
                        " parameter, provided class name: " + userProvidedClassName);
            }
        }
        initializeSetterMethods(streamDefinition, templateBuilderMap);
    }

    private void initializeSetterMethods(StreamDefinition streamDefinition, Map<String, TemplateBuilder>
            templateBuilderMap) {
        Attribute.Type attributeType = null;
        String attributeName = null;
        try {
            if (templateBuilderMap == null) {
                for (int i = 0; i < streamDefinition.getAttributeList().size(); i++) {
                    attributeType = streamDefinition.getAttributeList().get(i).getType(); //get attribute
                    // type
                    attributeName = streamDefinition.getAttributeNameArray()[i]; //get attribute name
                    attributeName = attributeName.substring(0, 1).toUpperCase() + attributeName.substring(1);
                    Method setterMethod;

                    if (attributeType == Attribute.Type.OBJECT) {
                        setterMethod = messageBuilderObject.getClass().getDeclaredMethod(GrpcConstants.PUTALL_METHOD + attributeName,
                                java.util.Map.class);
                    } else {
                        setterMethod = messageBuilderObject.getClass().getDeclaredMethod(GrpcConstants.SETTER + attributeName,
                                ProtobufUtils.getDataType(attributeType));
                    }
                    mappingPositionDataList.add(new MappingPositionData(setterMethod, i));
                }
            } else {
                List<String> mapKeySetList = new ArrayList<>(templateBuilderMap.keySet()); //convert keyset to a
                // list, to get keys by index
                for (int i = 0; i < templateBuilderMap.size(); i++) {
                    attributeName = mapKeySetList.get(i); //get attribute name
                    attributeType = templateBuilderMap.get(attributeName).getType();
                    attributeName = attributeName.substring(0, 1).toUpperCase() + attributeName.substring(1);
                    Method setterMethod;
                    if (attributeType == Attribute.Type.OBJECT) {
                        setterMethod = messageBuilderObject.getClass().getDeclaredMethod(GrpcConstants.PUTALL_METHOD + attributeName,
                                java.util.Map.class);
                    } else {
                        setterMethod = messageBuilderObject.getClass().getDeclaredMethod(GrpcConstants.SETTER + attributeName,
                                ProtobufUtils.getDataType(attributeType));
                    }
                    mappingPositionDataList.add(new MappingPositionDataWithTemplateBuilder(setterMethod,
                            templateBuilderMap.get(mapKeySetList.get(i))));
                }
            }
        } catch (NoSuchMethodException e) {
            Field[] fields = messageBuilderObject.getClass().getDeclaredFields(); //get all available
            // attributes
            String attributeTypeName = attributeType.name(); // this will not throw null pointer exception
            if (attributeType == Attribute.Type.OBJECT) {
                attributeTypeName = "Map";
            }
            throw new SiddhiAppRuntimeException(this.siddhiAppName + "Attribute name or type do " +
                    "not match with protobuf variable or type. provided attribute \"'" + attributeName +
                    "' :" +
                    " " + attributeTypeName + "\". Expected one of these attributes " +
                    protobufFieldsWithTypes(fields) + "," + e.getMessage(), e);
        }
    }

    @Override
    public Class[] getOutputEventClasses() {
        return new Class[]{GeneratedMessageV3.class};
    }

    @Override
    public void mapAndSend(Event[] events, OptionHolder optionHolder, Map<String, TemplateBuilder> templateBuilderMap,
                           SinkListener sinkListener) {
        for (Event event : events) {
            mapAndSend(event, optionHolder, templateBuilderMap, sinkListener);
        }
    }

    @Override
    public void mapAndSend(Event event, OptionHolder optionHolder, Map<String, TemplateBuilder> templateBuilderMap,
                           SinkListener sinkListener) {
        //todo error and just save the name
        for (MappingPositionData mappingPositionData : mappingPositionDataList) {
            Object data = mappingPositionData.getData(event);
            try {
                mappingPositionData.getMessageObjectSetterMethod().invoke(messageBuilderObject, data);
            } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
                String nameOfExpectedClass =
                        mappingPositionData.getMessageObjectSetterMethod().getParameterTypes()[0].getName();
                String nameOfFoundClass = data.getClass().getName();
                String[] foundClassnameArray = nameOfFoundClass.split("\\.");
                nameOfFoundClass = foundClassnameArray[foundClassnameArray.length - 1]; // to get the last name
                throw new SiddhiAppRuntimeException(this.siddhiAppName + " : Data type do not match. " +
                        "Expected data type : '" + nameOfExpectedClass + "' found : '" + nameOfFoundClass + "'," +
                        e.getMessage(), e);
            }

        }
        try {
            Method buildMethod = messageBuilderObject.getClass().getDeclaredMethod(GrpcConstants.BUILD_METHOD, null);
            Object messageObject = buildMethod.invoke(messageBuilderObject); //get the message object by invoking
            // build() method
            sinkListener.publish(messageObject);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // will not throw any error, all possible exceptions are handled in the init()
        }
    }

    private static class MappingPositionData {
        private Method messageObjectSetterMethod;
        private int position; //this attribute can be removed
        // TODO: 9/11/19 change the implementation, create two seperate classes

        private MappingPositionData(Method messageObjectSetterMethod, int position) {
            this.messageObjectSetterMethod = messageObjectSetterMethod;
            this.position = position; //if mapping is not available
        }

        private MappingPositionData(Method messageObjectSetterMethod) {
            this.messageObjectSetterMethod = messageObjectSetterMethod;
        }

        private Method getMessageObjectSetterMethod() {
            return messageObjectSetterMethod;
        }

        protected Object getData(Event event) {
            return event.getData(position);
        }
    }

    private static class MappingPositionDataWithTemplateBuilder extends MappingPositionData {
        private TemplateBuilder templateBuilder;

        private MappingPositionDataWithTemplateBuilder(Method messageObjectSetterMethod,
                                                       TemplateBuilder templateBuilder) {
            super(messageObjectSetterMethod);
            this.templateBuilder = templateBuilder;
        }

        @Override
        protected Object getData(Event event) {
            return templateBuilder.build(event);
        }
    }
}
