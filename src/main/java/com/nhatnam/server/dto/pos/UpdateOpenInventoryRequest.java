package com.nhatnam.server.dto.pos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
 public class UpdateOpenInventoryRequest {
     private Integer packQuantity = 0;
     private Integer unitQuantity = 0;
 }
