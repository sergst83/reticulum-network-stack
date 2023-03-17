package io.reticulum.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReticulumConf {

    @JsonProperty("share_instance")
    private Boolean shareInstance;

    @JsonProperty("shared_instance_port")
    private Integer sharedInstancePort;

    @JsonProperty("instance_control_port")
    private Integer instanceControlPort;

    @JsonProperty("enable_transport")
    private Boolean enableTransport;

    @JsonProperty("panic_on_interface_error")
    private Boolean panicOnInterfaceError;

    @JsonProperty("use_implicit_proof")
    private Boolean useImplicitProof;
}
